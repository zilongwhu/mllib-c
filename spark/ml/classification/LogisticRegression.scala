/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.classification

import scala.collection.mutable

import breeze.linalg.{DenseVector => BDV}
import breeze.optimize.{CachedDiffFunction, DiffFunction, LBFGS => BreezeLBFGS, OWLQN => BreezeOWLQN}
import org.apache.hadoop.fs.Path

import org.apache.spark.SparkException
import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.ml.feature.Instance
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.linalg.BLAS._
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.linalg.VectorImplicits._
import org.apache.spark.mllib.stat.MultivariateOnlineSummarizer
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.types.{DataType, DoubleType, StructType}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.VersionUtils

/**
 * Params for logistic regression.
 */
private[classification] trait LogisticRegressionParams extends ProbabilisticClassifierParams
  with HasRegParam with HasElasticNetParam with HasMaxIter with HasFitIntercept with HasTol
  with HasStandardization with HasWeightCol with HasThreshold with HasAggregationDepth {

  import org.apache.spark.ml.classification.LogisticRegression.supportedFamilyNames

  /**
   * Set threshold in binary classification, in range [0, 1].
   *
   * If the estimated probability of class label 1 is greater than threshold, then predict 1,
   * else 0. A high threshold encourages the model to predict 0 more often;
   * a low threshold encourages the model to predict 1 more often.
   *
   * Note: Calling this with threshold p is equivalent to calling `setThresholds(Array(1-p, p))`.
   *       When `setThreshold()` is called, any user-set value for `thresholds` will be cleared.
   *       If both `threshold` and `thresholds` are set in a ParamMap, then they must be
   *       equivalent.
   *
   * Default is 0.5.
   *
   * @group setParam
   */
  // TODO: Implement SPARK-11543?
  def setThreshold(value: Double): this.type = {
    if (isSet(thresholds)) clear(thresholds)
    set(threshold, value)
  }

  /**
   * Param for the name of family which is a description of the label distribution
   * to be used in the model.
   * Supported options:
   *  - "auto": Automatically select the family based on the number of classes:
   *            If numClasses == 1 || numClasses == 2, set to "binomial".
   *            Else, set to "multinomial"
   *  - "binomial": Binary logistic regression with pivoting.
   *  - "multinomial": Multinomial logistic (softmax) regression without pivoting.
   * Default is "auto".
   *
   * @group param
   */
  @Since("2.1.0")
  final val family: Param[String] = new Param(this, "family",
    "The name of family which is a description of the label distribution to be used in the " +
      s"model. Supported options: ${supportedFamilyNames.mkString(", ")}.",
    ParamValidators.inArray[String](supportedFamilyNames))

  /** @group getParam */
  @Since("2.1.0")
  def getFamily: String = $(family)

  /**
   * Get threshold for binary classification.
   *
   * If `thresholds` is set with length 2 (i.e., binary classification),
   * this returns the equivalent threshold: {{{1 / (1 + thresholds(0) / thresholds(1))}}}.
   * Otherwise, returns `threshold` if set, or its default value if unset.
   *
   * @group getParam
   * @throws IllegalArgumentException if `thresholds` is set to an array of length other than 2.
   */
  override def getThreshold: Double = {
    checkThresholdConsistency()
    if (isSet(thresholds)) {
      val ts = $(thresholds)
      require(ts.length == 2, "Logistic Regression getThreshold only applies to" +
        " binary classification, but thresholds has length != 2.  thresholds: " + ts.mkString(","))
      1.0 / (1.0 + ts(0) / ts(1))
    } else {
      $(threshold)
    }
  }

  /**
   * Set thresholds in multiclass (or binary) classification to adjust the probability of
   * predicting each class. Array must have length equal to the number of classes,
   * with values greater than 0, excepting that at most one value may be 0.
   * The class with largest value p/t is predicted, where p is the original probability of that
   * class and t is the class's threshold.
   *
   * Note: When `setThresholds()` is called, any user-set value for `threshold` will be cleared.
   *       If both `threshold` and `thresholds` are set in a ParamMap, then they must be
   *       equivalent.
   *
   * @group setParam
   */
  def setThresholds(value: Array[Double]): this.type = {
    if (isSet(threshold)) clear(threshold)
    set(thresholds, value)
  }

  /**
   * Get thresholds for binary or multiclass classification.
   *
   * If `thresholds` is set, return its value.
   * Otherwise, if `threshold` is set, return the equivalent thresholds for binary
   * classification: (1-threshold, threshold).
   * If neither are set, throw an exception.
   *
   * @group getParam
   */
  override def getThresholds: Array[Double] = {
    checkThresholdConsistency()
    if (!isSet(thresholds) && isSet(threshold)) {
      val t = $(threshold)
      Array(1-t, t)
    } else {
      $(thresholds)
    }
  }

  /**
   * If `threshold` and `thresholds` are both set, ensures they are consistent.
   *
   * @throws IllegalArgumentException if `threshold` and `thresholds` are not equivalent
   */
  protected def checkThresholdConsistency(): Unit = {
    if (isSet(threshold) && isSet(thresholds)) {
      val ts = $(thresholds)
      require(ts.length == 2, "Logistic Regression found inconsistent values for threshold and" +
        s" thresholds.  Param threshold is set (${$(threshold)}), indicating binary" +
        s" classification, but Param thresholds is set with length ${ts.length}." +
        " Clear one Param value to fix this problem.")
      val t = 1.0 / (1.0 + ts(0) / ts(1))
      require(math.abs($(threshold) - t) < 1E-5, "Logistic Regression getThreshold found" +
        s" inconsistent values for threshold (${$(threshold)}) and thresholds (equivalent to $t)")
    }
  }

  override protected def validateAndTransformSchema(
      schema: StructType,
      fitting: Boolean,
      featuresDataType: DataType): StructType = {
    checkThresholdConsistency()
    super.validateAndTransformSchema(schema, fitting, featuresDataType)
  }
}

/**
 * Logistic regression. Supports multinomial logistic (softmax) regression and binomial logistic
 * regression.
 */
@Since("1.2.0")
class LogisticRegression @Since("1.2.0") (
    @Since("1.4.0") override val uid: String)
  extends ProbabilisticClassifier[Vector, LogisticRegression, LogisticRegressionModel]
  with LogisticRegressionParams with DefaultParamsWritable with Logging {

  @Since("1.4.0")
  def this() = this(Identifiable.randomUID("logreg"))

  /**
   * Set the regularization parameter.
   * Default is 0.0.
   *
   * @group setParam
   */
  @Since("1.2.0")
  def setRegParam(value: Double): this.type = set(regParam, value)
  setDefault(regParam -> 0.0)

  /**
   * Set the ElasticNet mixing parameter.
   * For alpha = 0, the penalty is an L2 penalty.
   * For alpha = 1, it is an L1 penalty.
   * For alpha in (0,1), the penalty is a combination of L1 and L2.
   * Default is 0.0 which is an L2 penalty.
   *
   * @group setParam
   */
  @Since("1.4.0")
  def setElasticNetParam(value: Double): this.type = set(elasticNetParam, value)
  setDefault(elasticNetParam -> 0.0)

  /**
   * Set the maximum number of iterations.
   * Default is 100.
   *
   * @group setParam
   */
  @Since("1.2.0")
  def setMaxIter(value: Int): this.type = set(maxIter, value)
  setDefault(maxIter -> 100)

  /**
   * Set the convergence tolerance of iterations.
   * Smaller value will lead to higher accuracy with the cost of more iterations.
   * Default is 1E-6.
   *
   * @group setParam
   */
  @Since("1.4.0")
  def setTol(value: Double): this.type = set(tol, value)
  setDefault(tol -> 1E-6)

  /**
   * Whether to fit an intercept term.
   * Default is true.
   *
   * @group setParam
   */
  @Since("1.4.0")
  def setFitIntercept(value: Boolean): this.type = set(fitIntercept, value)
  setDefault(fitIntercept -> true)

  /**
   * Sets the value of param [[family]].
   * Default is "auto".
   *
   * @group setParam
   */
  @Since("2.1.0")
  def setFamily(value: String): this.type = set(family, value)
  setDefault(family -> "auto")

  /**
   * Whether to standardize the training features before fitting the model.
   * The coefficients of models will be always returned on the original scale,
   * so it will be transparent for users. Note that with/without standardization,
   * the models should be always converged to the same solution when no regularization
   * is applied. In R's GLMNET package, the default behavior is true as well.
   * Default is true.
   *
   * @group setParam
   */
  @Since("1.5.0")
  def setStandardization(value: Boolean): this.type = set(standardization, value)
  setDefault(standardization -> true)

  @Since("1.5.0")
  override def setThreshold(value: Double): this.type = super.setThreshold(value)

  @Since("1.5.0")
  override def getThreshold: Double = super.getThreshold

  /**
   * Sets the value of param [[weightCol]].
   * If this is not set or empty, we treat all instance weights as 1.0.
   * Default is not set, so all instances have weight one.
   *
   * @group setParam
   */
  @Since("1.6.0")
  def setWeightCol(value: String): this.type = set(weightCol, value)

  @Since("1.5.0")
  override def setThresholds(value: Array[Double]): this.type = super.setThresholds(value)

  @Since("1.5.0")
  override def getThresholds: Array[Double] = super.getThresholds

  /**
   * Suggested depth for treeAggregate (greater than or equal to 2).
   * If the dimensions of features or the number of partitions are large,
   * this param could be adjusted to a larger size.
   * Default is 2.
   *
   * @group expertSetParam
   */
  @Since("2.1.0")
  def setAggregationDepth(value: Int): this.type = set(aggregationDepth, value)
  setDefault(aggregationDepth -> 2)

  private var optInitialModel: Option[LogisticRegressionModel] = None

  private[spark] def setInitialModel(model: LogisticRegressionModel): this.type = {
    this.optInitialModel = Some(model)
    this
  }

  override protected[spark] def train(dataset: Dataset[_]): LogisticRegressionModel = {
    val handlePersistence = dataset.rdd.getStorageLevel == StorageLevel.NONE
    train(dataset, handlePersistence)
  }

  protected[spark] def train(
      dataset: Dataset[_],
      handlePersistence: Boolean): LogisticRegressionModel = {
    /* 样本 weight */
    val w = if (!isDefined(weightCol) || $(weightCol).isEmpty) lit(1.0) else col($(weightCol))
    /* 获取样本: (label, weight, features) */
    val instances: RDD[Instance] =
      dataset.select(col($(labelCol)), w, col($(featuresCol))).rdd.map {
        case Row(label: Double, weight: Double, features: Vector) =>
          Instance(label, weight, features)
      }
    /* cache */
    if (handlePersistence) instances.persist(StorageLevel.MEMORY_AND_DISK)

    val instr = Instrumentation.create(this, instances)
    instr.logParams(regParam, elasticNetParam, standardization, threshold,
      maxIter, tol, fitIntercept)

    val (summarizer, labelSummarizer) = {
      val seqOp = (c: (MultivariateOnlineSummarizer, MultiClassSummarizer),
        instance: Instance) =>
          (c._1.add(instance.features, instance.weight), c._2.add(instance.label, instance.weight))

      val combOp = (c1: (MultivariateOnlineSummarizer, MultiClassSummarizer),
        c2: (MultivariateOnlineSummarizer, MultiClassSummarizer)) =>
          (c1._1.merge(c2._1), c1._2.merge(c2._2))

      instances.treeAggregate(
        new MultivariateOnlineSummarizer, new MultiClassSummarizer
      )(seqOp, combOp, $(aggregationDepth))
    }

    val histogram = labelSummarizer.histogram /* 每个类别的weightSum */
    val numInvalid = labelSummarizer.countInvalid /* 错误label数(非负整数) */
    val numFeatures = summarizer.mean.size /* 特征数 */
    val numFeaturesPlusIntercept = if (getFitIntercept) numFeatures + 1 else numFeatures /* 加截距 */
    /* 类别数 */
    val numClasses = MetadataUtils.getNumClasses(dataset.schema($(labelCol))) match {
      case Some(n: Int) =>
        require(n >= histogram.length, s"Specified number of classes $n was " +
          s"less than the number of unique labels ${histogram.length}.")
        n
      case None => histogram.length
    }
    /* 多分类标记 */
    val isMultinomial = $(family) match {
      case "binomial" =>
        require(numClasses == 1 || numClasses == 2, s"Binomial family only supports 1 or 2 " +
        s"outcome classes but found $numClasses.")
        false
      case "multinomial" => true
      case "auto" => numClasses > 2
      case other => throw new IllegalArgumentException(s"Unsupported family: $other")
    }
    /* 多少份参数，二分类只有一份参数 */
    val numCoefficientSets = if (isMultinomial) numClasses else 1

    if (isDefined(thresholds)) {
      require($(thresholds).length == numClasses, this.getClass.getSimpleName +
        ".train() called with non-matching numClasses and thresholds.length." +
        s" numClasses=$numClasses, but thresholds has length ${$(thresholds).length}")
    }

    instr.logNumClasses(numClasses)
    instr.logNumFeatures(numFeatures)

    val (coefficientMatrix, interceptVector, objectiveHistory) = {
      if (numInvalid != 0) { /* 存在有错误label的样本 */
        val msg = s"Classification labels should be in [0 to ${numClasses - 1}]. " +
          s"Found $numInvalid invalid labels."
        logError(msg)
        throw new SparkException(msg)
      }
      /* 样本集中只有一个label */
      val isConstantLabel = histogram.count(_ != 0.0) == 1

      if ($(fitIntercept) && isConstantLabel) { /* 一个类目且拟合截距 */
        logWarning(s"All labels are the same value and fitIntercept=true, so the coefficients " +
          s"will be zeros. Training is not needed.")
        /* 找到label偏移位置 */
        val constantLabelIndex = Vectors.dense(histogram).argmax
        // TODO: use `compressed` after SPARK-17471
        val coefMatrix = if (numFeatures < numCoefficientSets) {
          new SparseMatrix(numCoefficientSets, numFeatures,
            Array.fill(numFeatures + 1)(0), Array.empty[Int], Array.empty[Double])
        } else {
          new SparseMatrix(numCoefficientSets, numFeatures, Array.fill(numCoefficientSets + 1)(0),
            Array.empty[Int], Array.empty[Double], isTransposed = true)
        }
        val interceptVec = if (isMultinomial) {
          Vectors.sparse(numClasses, Seq((constantLabelIndex, Double.PositiveInfinity)))
        } else {
          Vectors.dense(if (numClasses == 2) Double.PositiveInfinity else Double.NegativeInfinity)
        }
        (coefMatrix, interceptVec, Array.empty[Double])
      } else {
        if (!$(fitIntercept) && isConstantLabel) {
          logWarning(s"All labels belong to a single class and fitIntercept=false. It's a " +
            s"dangerous ground, so the algorithm may not converge.")
        }

        val featuresMean = summarizer.mean.toArray /* 特征均值 */
        val featuresStd = summarizer.variance.toArray.map(math.sqrt) /* 特征标准差 */

        if (!$(fitIntercept) && (0 until numFeatures).exists { i =>
          featuresStd(i) == 0.0 && featuresMean(i) != 0.0 }) {
          /* 存在非零特征，所有样本取值一样（方差为0），并且没有指定拟合截距，此时，非零常量特征权重取0； */
          logWarning("Fitting LogisticRegressionModel without intercept on dataset with " +
            "constant nonzero column, Spark MLlib outputs zero coefficients for constant " +
            "nonzero columns. This behavior is the same as R glmnet but different from LIBSVM.")
        }

        val regParamL1 = $(elasticNetParam) * $(regParam) /* 正则一 */
        val regParamL2 = (1.0 - $(elasticNetParam)) * $(regParam) /* 正则二 */

        val bcFeaturesStd = instances.context.broadcast(featuresStd) /* 广播特征标准差 */
        /* 损失函数：计算损失和梯度 */
        val costFun = new LogisticCostFun(instances, numClasses, $(fitIntercept),
          $(standardization), bcFeaturesStd, regParamL2, multinomial = isMultinomial,
          $(aggregationDepth))

        val optimizer = if ($(elasticNetParam) == 0.0 || $(regParam) == 0.0) {
          new BreezeLBFGS[BDV[Double]]($(maxIter), 10, $(tol))
        } else {
          val standardizationParam = $(standardization)
          def regParamL1Fun = (index: Int) => {
            // Remove the L1 penalization on the intercept
            val isIntercept = $(fitIntercept) && index >= numFeatures * numCoefficientSets
            if (isIntercept) {
              0.0
            } else {
              if (standardizationParam) {
                regParamL1
              } else {
                val featureIndex = index / numCoefficientSets
                // If `standardization` is false, we still standardize the data
                // to improve the rate of convergence; as a result, we have to
                // perform this reverse standardization by penalizing each component
                // differently to get effectively the same objective function when
                // the training dataset is not standardized.
                if (featuresStd(featureIndex) != 0.0) {
                  regParamL1 / featuresStd(featureIndex)
                } else {
                  0.0
                }
              }
            }
          }
          new BreezeOWLQN[Int, BDV[Double]]($(maxIter), 10, regParamL1Fun, $(tol))
        }

        /*
          The coefficients are laid out in column major order during training. Here we initialize
          a column major matrix of initial coefficients.
         */
        val initialCoefWithInterceptMatrix =
          Matrices.zeros(numCoefficientSets, numFeaturesPlusIntercept)

        val initialModelIsValid = optInitialModel match {
          case Some(_initialModel) =>
            /* 检查初始模型是否匹配: 类目数/特征数/是否拟合截距 */
            val providedCoefs = _initialModel.coefficientMatrix
            val modelIsValid = (providedCoefs.numRows == numCoefficientSets) &&
              (providedCoefs.numCols == numFeatures) &&
              (_initialModel.interceptVector.size == numCoefficientSets) &&
              (_initialModel.getFitIntercept == $(fitIntercept))
            if (!modelIsValid) {
              logWarning(s"Initial coefficients will be ignored! Its dimensions " +
                s"(${providedCoefs.numRows}, ${providedCoefs.numCols}) did not match the " +
                s"expected size ($numCoefficientSets, $numFeatures)")
            }
            modelIsValid
          case None => false
        }

        if (initialModelIsValid) {
          val providedCoef = optInitialModel.get.coefficientMatrix
          providedCoef.foreachActive { (classIndex, featureIndex, value) =>
            // We need to scale the coefficients since they will be trained in the scaled space
            initialCoefWithInterceptMatrix.update(classIndex, featureIndex,
              value * featuresStd(featureIndex)) /* 特征统一归一化后训练的，所以这里需要先调整参数 */
          }
          if ($(fitIntercept)) { /* 拷贝截距权重，截距不会归一 */
            optInitialModel.get.interceptVector.foreachActive { (classIndex, value) =>
              initialCoefWithInterceptMatrix.update(classIndex, numFeatures, value)
            }
          }
        } else if ($(fitIntercept) && isMultinomial) {
          /*
             For multinomial logistic regression, when we initialize the coefficients as zeros,
             it will converge faster if we initialize the intercepts such that
             it follows the distribution of the labels.
             {{{
               P(1) = \exp(b_1) / Z
               ...
               P(K) = \exp(b_K) / Z
               where Z = \sum_{k=1}^{K} \exp(b_k)
             }}}
             Since this doesn't have a unique solution, one of the solutions that satisfies the
             above equations is
             {{{
               \exp(b_k) = count_k * \exp(\lambda)
               b_k = \log(count_k) * \lambda
             }}}
             \lambda is a free parameter, so choose the phase \lambda such that the
             mean is centered. This yields
             {{{
               b_k = \log(count_k)
               b_k' = b_k - \mean(b_k)
             }}}
           */
          /* 初始化截距 */
          val rawIntercepts = histogram.map(c => math.log(c + 1)) // add 1 for smoothing
          val rawMean = rawIntercepts.sum / rawIntercepts.length
          rawIntercepts.indices.foreach { i =>
            initialCoefWithInterceptMatrix.update(i, numFeatures, rawIntercepts(i) - rawMean)
          }
        } else if ($(fitIntercept)) {
          /*
             For binary logistic regression, when we initialize the coefficients as zeros,
             it will converge faster if we initialize the intercept such that
             it follows the distribution of the labels.

             {{{
               P(0) = 1 / (1 + \exp(b)), and
               P(1) = \exp(b) / (1 + \exp(b))
             }}}, hence
             {{{
               b = \log{P(1) / P(0)} = \log{count_1 / count_0}
             }}}
           */
          /* 初始化截距: log(odds) */
          initialCoefWithInterceptMatrix.update(0, numFeatures,
            math.log(histogram(1) / histogram(0)))
        }

        val states = optimizer.iterations(new CachedDiffFunction(costFun),
          new BDV[Double](initialCoefWithInterceptMatrix.toArray))

        /*
           Note that in Logistic Regression, the objective history (loss + regularization)
           is log-likelihood which is invariant under feature standardization. As a result,
           the objective history from optimizer is the same as the one in the original space.
         */
        val arrayBuilder = mutable.ArrayBuilder.make[Double]
        var state: optimizer.State = null
        while (states.hasNext) {
          state = states.next()
          arrayBuilder += state.adjustedValue
        }
        bcFeaturesStd.destroy(blocking = false)

        if (state == null) {
          val msg = s"${optimizer.getClass.getName} failed."
          logError(msg)
          throw new SparkException(msg)
        }

        /*
           The coefficients are trained in the scaled space; we're converting them back to
           the original space.

           Additionally, since the coefficients were laid out in column major order during training
           to avoid extra computation, we convert them back to row major before passing them to the
           model.

           Note that the intercept in scaled space and original space is the same;
           as a result, no scaling is needed.
         */
        val allCoefficients = state.x.toArray.clone()
        val allCoefMatrix = new DenseMatrix(numCoefficientSets, numFeaturesPlusIntercept,
          allCoefficients)
        val denseCoefficientMatrix = new DenseMatrix(numCoefficientSets, numFeatures,
          new Array[Double](numCoefficientSets * numFeatures), isTransposed = true)
        val interceptVec = if ($(fitIntercept) || !isMultinomial) {
          Vectors.zeros(numCoefficientSets)
        } else {
          Vectors.sparse(numCoefficientSets, Seq())
        }
        // separate intercepts and coefficients from the combined matrix
        allCoefMatrix.foreachActive { (classIndex, featureIndex, value) =>
          val isIntercept = $(fitIntercept) && (featureIndex == numFeatures)
          if (!isIntercept && featuresStd(featureIndex) != 0.0) {
            denseCoefficientMatrix.update(classIndex, featureIndex,
              value / featuresStd(featureIndex)) /* 训练时是在归一化空间，返回参数权重时，需要调整； */
          }
          if (isIntercept) interceptVec.toArray(classIndex) = value
        }

        if ($(regParam) == 0.0 && isMultinomial) {
          /*
            When no regularization is applied, the multinomial coefficients lack identifiability
            because we do not use a pivot class. We can add any constant value to the coefficients
            and get the same likelihood. So here, we choose the mean centered coefficients for
            reproducibility. This method follows the approach in glmnet, described here:

            Friedman, et al. "Regularization Paths for Generalized Linear Models via
              Coordinate Descent," https://core.ac.uk/download/files/153/6287975.pdf
           */
          val denseValues = denseCoefficientMatrix.values
          val coefficientMean = denseValues.sum / denseValues.length /* 均值 */
          denseCoefficientMatrix.update(_ - coefficientMean) /* 矫正 */
        }

        // TODO: use `denseCoefficientMatrix.compressed` after SPARK-17471
        val compressedCoefficientMatrix = if (isMultinomial) {
          denseCoefficientMatrix
        } else {
          val compressedVector = Vectors.dense(denseCoefficientMatrix.values).compressed
          compressedVector match {
            case dv: DenseVector => denseCoefficientMatrix
            case sv: SparseVector =>
              new SparseMatrix(1, numFeatures, Array(0, sv.indices.length), sv.indices, sv.values,
                isTransposed = true)
          }
        }

        // center the intercepts when using multinomial algorithm
        if ($(fitIntercept) && isMultinomial) {
          val interceptArray = interceptVec.toArray
          val interceptMean = interceptArray.sum / interceptArray.length /* 截距均值 */
          (0 until interceptVec.size).foreach { i => interceptArray(i) -= interceptMean } /* 矫正 */
        }
        /* 参数, 截距, 目标函数历史值 */
        (compressedCoefficientMatrix, interceptVec.compressed, arrayBuilder.result())
      }
    }

    if (handlePersistence) instances.unpersist()

    val model = copyValues(new LogisticRegressionModel(uid, coefficientMatrix, interceptVector,
      numClasses, isMultinomial))
    // TODO: implement summary model for multinomial case
    val m = if (!isMultinomial) {
      /* 二分类统计信息: 可获取AUC等评估指标 */
      val (summaryModel, probabilityColName) = model.findSummaryModelAndProbabilityCol()
      val logRegSummary = new BinaryLogisticRegressionTrainingSummary(
        summaryModel.transform(dataset),
        probabilityColName,
        $(labelCol),
        $(featuresCol),
        objectiveHistory)
      model.setSummary(Some(logRegSummary))
    } else {
      model
    }
    instr.logSuccess(m)
    m
  }

  @Since("1.4.0")
  override def copy(extra: ParamMap): LogisticRegression = defaultCopy(extra)
}

@Since("1.6.0")
object LogisticRegression extends DefaultParamsReadable[LogisticRegression] {

  @Since("1.6.0")
  override def load(path: String): LogisticRegression = super.load(path)

  private[classification] val supportedFamilyNames =
    Array("auto", "binomial", "multinomial").map(_.toLowerCase)
}

/**
 * Model produced by [[LogisticRegression]].
 */
@Since("1.4.0")
class LogisticRegressionModel private[spark] (
    @Since("1.4.0") override val uid: String,
    @Since("2.1.0") val coefficientMatrix: Matrix,
    @Since("2.1.0") val interceptVector: Vector,
    @Since("1.3.0") override val numClasses: Int,
    private val isMultinomial: Boolean)
  extends ProbabilisticClassificationModel[Vector, LogisticRegressionModel]
  with LogisticRegressionParams with MLWritable {

  require(coefficientMatrix.numRows == interceptVector.size, s"Dimension mismatch! Expected " +
    s"coefficientMatrix.numRows == interceptVector.size, but ${coefficientMatrix.numRows} != " +
    s"${interceptVector.size}")

  private[spark] def this(uid: String, coefficients: Vector, intercept: Double) =
    this(uid, new DenseMatrix(1, coefficients.size, coefficients.toArray, isTransposed = true),
      Vectors.dense(intercept), 2, isMultinomial = false)

  /**
   * A vector of model coefficients for "binomial" logistic regression. If this model was trained
   * using the "multinomial" family then an exception is thrown.
   *
   * @return Vector
   */
  @Since("2.0.0")
  def coefficients: Vector = if (isMultinomial) {
    throw new SparkException("Multinomial models contain a matrix of coefficients, use " +
      "coefficientMatrix instead.")
  } else {
    _coefficients
  }

  // convert to appropriate vector representation without replicating data
  private lazy val _coefficients: Vector = {
    require(coefficientMatrix.isTransposed,
      "LogisticRegressionModel coefficients should be row major.")
    coefficientMatrix match {
      case dm: DenseMatrix => Vectors.dense(dm.values)
      case sm: SparseMatrix => Vectors.sparse(coefficientMatrix.numCols, sm.rowIndices, sm.values)
    }
  }

  /**
   * The model intercept for "binomial" logistic regression. If this model was fit with the
   * "multinomial" family then an exception is thrown.
   *
   * @return Double
   */
  @Since("1.3.0")
  def intercept: Double = if (isMultinomial) {
    throw new SparkException("Multinomial models contain a vector of intercepts, use " +
      "interceptVector instead.")
  } else {
    _intercept
  }

  private lazy val _intercept = interceptVector.toArray.head

  @Since("1.5.0")
  override def setThreshold(value: Double): this.type = super.setThreshold(value)

  @Since("1.5.0")
  override def getThreshold: Double = super.getThreshold

  @Since("1.5.0")
  override def setThresholds(value: Array[Double]): this.type = super.setThresholds(value)

  @Since("1.5.0")
  override def getThresholds: Array[Double] = super.getThresholds

  /** Margin (rawPrediction) for class label 1.  For binary classification only. */
  private val margin: Vector => Double = (features) => {
    BLAS.dot(features, _coefficients) + _intercept
  }

  /** Margin (rawPrediction) for each class label. */
  private val margins: Vector => Vector = (features) => {
    val m = interceptVector.toDense.copy
    BLAS.gemv(1.0, coefficientMatrix, features, 1.0, m)
    m
  }

  /** Score (probability) for class label 1.  For binary classification only. */
  private val score: Vector => Double = (features) => {
    val m = margin(features)
    1.0 / (1.0 + math.exp(-m))
  }

  @Since("1.6.0")
  override val numFeatures: Int = coefficientMatrix.numCols

  private var trainingSummary: Option[LogisticRegressionTrainingSummary] = None

  /**
   * Gets summary of model on training set. An exception is
   * thrown if `trainingSummary == None`.
   */
  @Since("1.5.0")
  def summary: LogisticRegressionTrainingSummary = trainingSummary.getOrElse {
    throw new SparkException("No training summary available for this LogisticRegressionModel")
  }

  /**
   * If the probability column is set returns the current model and probability column,
   * otherwise generates a new column and sets it as the probability column on a new copy
   * of the current model.
   */
  private[classification] def findSummaryModelAndProbabilityCol():
      (LogisticRegressionModel, String) = {
    $(probabilityCol) match {
      case "" =>
        val probabilityColName = "probability_" + java.util.UUID.randomUUID.toString
        (copy(ParamMap.empty).setProbabilityCol(probabilityColName), probabilityColName)
      case p => (this, p)
    }
  }

  private[classification]
  def setSummary(summary: Option[LogisticRegressionTrainingSummary]): this.type = {
    this.trainingSummary = summary
    this
  }

  /** Indicates whether a training summary exists for this model instance. */
  @Since("1.5.0")
  def hasSummary: Boolean = trainingSummary.isDefined

  /**
   * Evaluates the model on a test dataset.
   *
   * @param dataset Test dataset to evaluate model on.
   */
  @Since("2.0.0")
  def evaluate(dataset: Dataset[_]): LogisticRegressionSummary = {
    // Handle possible missing or invalid prediction columns
    val (summaryModel, probabilityColName) = findSummaryModelAndProbabilityCol()
    new BinaryLogisticRegressionSummary(summaryModel.transform(dataset),
      probabilityColName, $(labelCol), $(featuresCol))
  }

  /**
   * Predict label for the given feature vector.
   * The behavior of this can be adjusted using `thresholds`.
   */
  override protected def predict(features: Vector): Double = if (isMultinomial) {
    super.predict(features)
  } else {
    // Note: We should use getThreshold instead of $(threshold) since getThreshold is overridden.
    if (score(features) > getThreshold) 1 else 0
  }

  override protected def raw2probabilityInPlace(rawPrediction: Vector): Vector = {
    rawPrediction match {
      case dv: DenseVector =>
        if (isMultinomial) {
          val size = dv.size
          val values = dv.values

          // get the maximum margin
          val maxMarginIndex = rawPrediction.argmax
          val maxMargin = rawPrediction(maxMarginIndex)

          if (maxMargin == Double.PositiveInfinity) {
            var k = 0
            while (k < size) {
              values(k) = if (k == maxMarginIndex) 1.0 else 0.0
              k += 1
            }
          } else {
            val sum = {
              var temp = 0.0
              var k = 0
              while (k < numClasses) {
                values(k) = if (maxMargin > 0) {
                  math.exp(values(k) - maxMargin)
                } else {
                  math.exp(values(k))
                }
                temp += values(k)
                k += 1
              }
              temp
            }
            BLAS.scal(1 / sum, dv)
          }
          dv
        } else {
          var i = 0
          val size = dv.size
          while (i < size) {
            dv.values(i) = 1.0 / (1.0 + math.exp(-dv.values(i)))
            i += 1
          }
          dv
        }
      case sv: SparseVector =>
        throw new RuntimeException("Unexpected error in LogisticRegressionModel:" +
          " raw2probabilitiesInPlace encountered SparseVector")
    }
  }

  override protected def predictRaw(features: Vector): Vector = {
    if (isMultinomial) {
      margins(features)
    } else {
      val m = margin(features)
      Vectors.dense(-m, m)
    }
  }

  @Since("1.4.0")
  override def copy(extra: ParamMap): LogisticRegressionModel = {
    val newModel = copyValues(new LogisticRegressionModel(uid, coefficientMatrix, interceptVector,
      numClasses, isMultinomial), extra)
    newModel.setSummary(trainingSummary).setParent(parent)
  }

  override protected def raw2prediction(rawPrediction: Vector): Double = {
    if (isMultinomial) {
      super.raw2prediction(rawPrediction)
    } else {
      // Note: We should use getThreshold instead of $(threshold) since getThreshold is overridden.
      val t = getThreshold
      val rawThreshold = if (t == 0.0) {
        Double.NegativeInfinity
      } else if (t == 1.0) {
        Double.PositiveInfinity
      } else {
        math.log(t / (1.0 - t))
      }
      if (rawPrediction(1) > rawThreshold) 1 else 0
    }
  }

  override protected def probability2prediction(probability: Vector): Double = {
    if (isMultinomial) {
      super.probability2prediction(probability)
    } else {
      // Note: We should use getThreshold instead of $(threshold) since getThreshold is overridden.
      if (probability(1) > getThreshold) 1 else 0
    }
  }

  /**
   * Returns a [[org.apache.spark.ml.util.MLWriter]] instance for this ML instance.
   *
   * For [[LogisticRegressionModel]], this does NOT currently save the training [[summary]].
   * An option to save [[summary]] may be added in the future.
   *
   * This also does not save the [[parent]] currently.
   */
  @Since("1.6.0")
  override def write: MLWriter = new LogisticRegressionModel.LogisticRegressionModelWriter(this)
}


@Since("1.6.0")
object LogisticRegressionModel extends MLReadable[LogisticRegressionModel] {

  @Since("1.6.0")
  override def read: MLReader[LogisticRegressionModel] = new LogisticRegressionModelReader

  @Since("1.6.0")
  override def load(path: String): LogisticRegressionModel = super.load(path)

  /** [[MLWriter]] instance for [[LogisticRegressionModel]] */
  private[LogisticRegressionModel]
  class LogisticRegressionModelWriter(instance: LogisticRegressionModel)
    extends MLWriter with Logging {

    private case class Data(
        numClasses: Int,
        numFeatures: Int,
        interceptVector: Vector,
        coefficientMatrix: Matrix,
        isMultinomial: Boolean)

    override protected def saveImpl(path: String): Unit = {
      // Save metadata and Params
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      // Save model data: numClasses, numFeatures, intercept, coefficients
      val data = Data(instance.numClasses, instance.numFeatures, instance.interceptVector,
        instance.coefficientMatrix, instance.isMultinomial)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class LogisticRegressionModelReader extends MLReader[LogisticRegressionModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[LogisticRegressionModel].getName

    override def load(path: String): LogisticRegressionModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val (major, minor) = VersionUtils.majorMinorVersion(metadata.sparkVersion)

      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.format("parquet").load(dataPath)

      val model = if (major.toInt < 2 || (major.toInt == 2 && minor.toInt == 0)) {
        // 2.0 and before
        val Row(numClasses: Int, numFeatures: Int, intercept: Double, coefficients: Vector) =
          MLUtils.convertVectorColumnsToML(data, "coefficients")
            .select("numClasses", "numFeatures", "intercept", "coefficients")
            .head()
        val coefficientMatrix =
          new DenseMatrix(1, coefficients.size, coefficients.toArray, isTransposed = true)
        val interceptVector = Vectors.dense(intercept)
        new LogisticRegressionModel(metadata.uid, coefficientMatrix,
          interceptVector, numClasses, isMultinomial = false)
      } else {
        // 2.1+
        val Row(numClasses: Int, numFeatures: Int, interceptVector: Vector,
        coefficientMatrix: Matrix, isMultinomial: Boolean) = data
          .select("numClasses", "numFeatures", "interceptVector", "coefficientMatrix",
            "isMultinomial").head()
        new LogisticRegressionModel(metadata.uid, coefficientMatrix, interceptVector,
          numClasses, isMultinomial)
      }

      DefaultParamsReader.getAndSetParams(model, metadata)
      model
    }
  }
}


/**
 * MultiClassSummarizer computes the number of distinct labels and corresponding counts,
 * and validates the data to see if the labels used for k class multi-label classification
 * are in the range of {0, 1, ..., k - 1} in an online fashion.
 *
 * Two MultilabelSummarizer can be merged together to have a statistical summary of the
 * corresponding joint dataset.
 */
private[classification] class MultiClassSummarizer extends Serializable {
  // The first element of value in distinctMap is the actually number of instances,
  // and the second element of value is sum of the weights.
  private val distinctMap = new mutable.HashMap[Int, (Long, Double)] /* label => count, weightSum */
  private var totalInvalidCnt: Long = 0L

  /**
   * Add a new label into this MultilabelSummarizer, and update the distinct map.
   *
   * @param label The label for this data point.
   * @param weight The weight of this instances.
   * @return This MultilabelSummarizer
   */
  def add(label: Double, weight: Double = 1.0): this.type = {
    require(weight >= 0.0, s"instance weight, $weight has to be >= 0.0")

    if (weight == 0.0) return this
    /* label必须为非负整数 */
    if (label - label.toInt != 0.0 || label < 0) {
      totalInvalidCnt += 1
      this
    }
    else {
      val (counts: Long, weightSum: Double) = distinctMap.getOrElse(label.toInt, (0L, 0.0))
      distinctMap.put(label.toInt, (counts + 1L, weightSum + weight))
      this
    }
  }

  /**
   * Merge another MultilabelSummarizer, and update the distinct map.
   * (Note that it will merge the smaller distinct map into the larger one using in-place
   * merging, so either `this` or `other` object will be modified and returned.)
   *
   * @param other The other MultilabelSummarizer to be merged.
   * @return Merged MultilabelSummarizer object.
   */
  def merge(other: MultiClassSummarizer): MultiClassSummarizer = {
    val (largeMap, smallMap) = if (this.distinctMap.size > other.distinctMap.size) {
      (this, other)
    } else {
      (other, this)
    }
    smallMap.distinctMap.foreach {
      case (key, value) =>
        val (counts: Long, weightSum: Double) = largeMap.distinctMap.getOrElse(key, (0L, 0.0))
        largeMap.distinctMap.put(key, (counts + value._1, weightSum + value._2))
    }
    largeMap.totalInvalidCnt += smallMap.totalInvalidCnt
    largeMap
  }

  /** @return The total invalid input counts. */
  def countInvalid: Long = totalInvalidCnt

  /** @return The number of distinct labels in the input dataset. */
  def numClasses: Int = if (distinctMap.isEmpty) 0 else distinctMap.keySet.max + 1 /* class从0开始 */

  /** @return The weightSum of each label in the input dataset. */
  def histogram: Array[Double] = { /* 获取每个class的weightSum  */
    val result = Array.ofDim[Double](numClasses)
    var i = 0
    val len = result.length
    while (i < len) {
      result(i) = distinctMap.getOrElse(i, (0L, 0.0))._2
      i += 1
    }
    result
  }
}

/**
 * Abstraction for multinomial Logistic Regression Training results.
 * Currently, the training summary ignores the training weights except
 * for the objective trace.
 */
sealed trait LogisticRegressionTrainingSummary extends LogisticRegressionSummary {

  /** objective function (scaled loss + regularization) at each iteration. */
  def objectiveHistory: Array[Double]

  /** Number of training iterations until termination */
  def totalIterations: Int = objectiveHistory.length

}

/**
 * Abstraction for Logistic Regression Results for a given model.
 */
sealed trait LogisticRegressionSummary extends Serializable {

  /**
   * Dataframe output by the model's `transform` method.
   */
  def predictions: DataFrame

  /** Field in "predictions" which gives the probability of each class as a vector. */
  def probabilityCol: String

  /** Field in "predictions" which gives the true label of each instance (if available). */
  def labelCol: String

  /** Field in "predictions" which gives the features of each instance as a vector. */
  def featuresCol: String

}

/**
 * :: Experimental ::
 * Logistic regression training results.
 *
 * @param predictions dataframe output by the model's `transform` method.
 * @param probabilityCol field in "predictions" which gives the probability of
 *                       each class as a vector.
 * @param labelCol field in "predictions" which gives the true label of each instance.
 * @param featuresCol field in "predictions" which gives the features of each instance as a vector.
 * @param objectiveHistory objective function (scaled loss + regularization) at each iteration.
 */
@Experimental
@Since("1.5.0")
class BinaryLogisticRegressionTrainingSummary private[classification] (
    predictions: DataFrame,
    probabilityCol: String,
    labelCol: String,
    featuresCol: String,
    @Since("1.5.0") val objectiveHistory: Array[Double])
  extends BinaryLogisticRegressionSummary(predictions, probabilityCol, labelCol, featuresCol)
  with LogisticRegressionTrainingSummary {

}

/**
 * :: Experimental ::
 * Binary Logistic regression results for a given model.
 *
 * @param predictions dataframe output by the model's `transform` method.
 * @param probabilityCol field in "predictions" which gives the probability of
 *                       each class as a vector.
 * @param labelCol field in "predictions" which gives the true label of each instance.
 * @param featuresCol field in "predictions" which gives the features of each instance as a vector.
 */
@Experimental
@Since("1.5.0")
class BinaryLogisticRegressionSummary private[classification] (
    @Since("1.5.0") @transient override val predictions: DataFrame,
    @Since("1.5.0") override val probabilityCol: String,
    @Since("1.5.0") override val labelCol: String,
    @Since("1.6.0") override val featuresCol: String) extends LogisticRegressionSummary {


  private val sparkSession = predictions.sparkSession
  import sparkSession.implicits._

  /**
   * Returns a BinaryClassificationMetrics object.
   */
  // TODO: Allow the user to vary the number of bins using a setBins method in
  // BinaryClassificationMetrics. For now the default is set to 100.
  @transient private val binaryMetrics = new BinaryClassificationMetrics(
    predictions.select(col(probabilityCol), col(labelCol).cast(DoubleType)).rdd.map {
      case Row(score: Vector, label: Double) => (score(1), label)
    }, 100
  )

  /**
   * Returns the receiver operating characteristic (ROC) curve,
   * which is a Dataframe having two fields (FPR, TPR)
   * with (0.0, 0.0) prepended and (1.0, 1.0) appended to it.
   * See http://en.wikipedia.org/wiki/Receiver_operating_characteristic
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  @transient lazy val roc: DataFrame = binaryMetrics.roc().toDF("FPR", "TPR")

  /**
   * Computes the area under the receiver operating characteristic (ROC) curve.
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  lazy val areaUnderROC: Double = binaryMetrics.areaUnderROC()

  /**
   * Returns the precision-recall curve, which is a Dataframe containing
   * two fields recall, precision with (0.0, 1.0) prepended to it.
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  @transient lazy val pr: DataFrame = binaryMetrics.pr().toDF("recall", "precision")

  /**
   * Returns a dataframe with two fields (threshold, F-Measure) curve with beta = 1.0.
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  @transient lazy val fMeasureByThreshold: DataFrame = {
    binaryMetrics.fMeasureByThreshold().toDF("threshold", "F-Measure")
  }

  /**
   * Returns a dataframe with two fields (threshold, precision) curve.
   * Every possible probability obtained in transforming the dataset are used
   * as thresholds used in calculating the precision.
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  @transient lazy val precisionByThreshold: DataFrame = {
    binaryMetrics.precisionByThreshold().toDF("threshold", "precision")
  }

  /**
   * Returns a dataframe with two fields (threshold, recall) curve.
   * Every possible probability obtained in transforming the dataset are used
   * as thresholds used in calculating the recall.
   *
   * @note This ignores instance weights (setting all to 1.0) from `LogisticRegression.weightCol`.
   * This will change in later Spark versions.
   */
  @Since("1.5.0")
  @transient lazy val recallByThreshold: DataFrame = {
    binaryMetrics.recallByThreshold().toDF("threshold", "recall")
  }
}

/**
 * LogisticAggregator computes the gradient and loss for binary or multinomial logistic (softmax)
 * loss function, as used in classification for instances in sparse or dense vector in an online
 * fashion.
 *
 * Two LogisticAggregators can be merged together to have a summary of loss and gradient of
 * the corresponding joint dataset.
 *
 * For improving the convergence rate during the optimization process and also to prevent against
 * features with very large variances exerting an overly large influence during model training,
 * packages like R's GLMNET perform the scaling to unit variance and remove the mean in order to
 * reduce the condition number. The model is then trained in this scaled space, but returns the
 * coefficients in the original scale. See page 9 in
 * http://cran.r-project.org/web/packages/glmnet/glmnet.pdf
 *
 * However, we don't want to apply the [[org.apache.spark.ml.feature.StandardScaler]] on the
 * training dataset, and then cache the standardized dataset since it will create a lot of overhead.
 * As a result, we perform the scaling implicitly when we compute the objective function (though
 * we do not subtract the mean).
 *
 * Note that there is a difference between multinomial (softmax) and binary loss. The binary case
 * uses one outcome class as a "pivot" and regresses the other class against the pivot. In the
 * multinomial case, the softmax loss function is used to model each class probability
 * independently. Using softmax loss produces `K` sets of coefficients, while using a pivot class
 * produces `K - 1` sets of coefficients (a single coefficient vector in the binary case). In the
 * binary case, we can say that the coefficients are shared between the positive and negative
 * classes. When regularization is applied, multinomial (softmax) loss will produce a result
 * different from binary loss since the positive and negative don't share the coefficients while the
 * binary regression shares the coefficients between positive and negative.
 *
 * The following is a mathematical derivation for the multinomial (softmax) loss.
 *
 * The probability of the multinomial outcome $y$ taking on any of the K possible outcomes is:
 *
 * <blockquote>
 *    $$
 *    P(y_i=0|\vec{x}_i, \beta) = \frac{e^{\vec{x}_i^T \vec{\beta}_0}}{\sum_{k=0}^{K-1}
 *       e^{\vec{x}_i^T \vec{\beta}_k}} \\
 *    P(y_i=1|\vec{x}_i, \beta) = \frac{e^{\vec{x}_i^T \vec{\beta}_1}}{\sum_{k=0}^{K-1}
 *       e^{\vec{x}_i^T \vec{\beta}_k}}\\
 *    P(y_i=K-1|\vec{x}_i, \beta) = \frac{e^{\vec{x}_i^T \vec{\beta}_{K-1}}\,}{\sum_{k=0}^{K-1}
 *       e^{\vec{x}_i^T \vec{\beta}_k}}
 *    $$
 * </blockquote>
 *
 * The model coefficients $\beta = (\beta_0, \beta_1, \beta_2, ..., \beta_{K-1})$ become a matrix
 * which has dimension of $K \times (N+1)$ if the intercepts are added. If the intercepts are not
 * added, the dimension will be $K \times N$.
 *
 * Note that the coefficients in the model above lack identifiability. That is, any constant scalar
 * can be added to all of the coefficients and the probabilities remain the same.
 *
 * <blockquote>
 *    $$
 *    \begin{align}
 *    \frac{e^{\vec{x}_i^T \left(\vec{\beta}_0 + \vec{c}\right)}}{\sum_{k=0}^{K-1}
 *       e^{\vec{x}_i^T \left(\vec{\beta}_k + \vec{c}\right)}}
 *    = \frac{e^{\vec{x}_i^T \vec{\beta}_0}e^{\vec{x}_i^T \vec{c}}\,}{e^{\vec{x}_i^T \vec{c}}
 *       \sum_{k=0}^{K-1} e^{\vec{x}_i^T \vec{\beta}_k}}
 *    = \frac{e^{\vec{x}_i^T \vec{\beta}_0}}{\sum_{k=0}^{K-1} e^{\vec{x}_i^T \vec{\beta}_k}}
 *    \end{align}
 *    $$
 * </blockquote>
 *
 * However, when regularization is added to the loss function, the coefficients are indeed
 * identifiable because there is only one set of coefficients which minimizes the regularization
 * term. When no regularization is applied, we choose the coefficients with the minimum L2
 * penalty for consistency and reproducibility. For further discussion see:
 *
 * Friedman, et al. "Regularization Paths for Generalized Linear Models via Coordinate Descent"
 *
 * The loss of objective function for a single instance of data (we do not include the
 * regularization term here for simplicity) can be written as
 *
 * <blockquote>
 *    $$
 *    \begin{align}
 *    \ell\left(\beta, x_i\right) &= -log{P\left(y_i \middle| \vec{x}_i, \beta\right)} \\
 *    &= log\left(\sum_{k=0}^{K-1}e^{\vec{x}_i^T \vec{\beta}_k}\right) - \vec{x}_i^T \vec{\beta}_y\\
 *    &= log\left(\sum_{k=0}^{K-1} e^{margins_k}\right) - margins_y
 *    \end{align}
 *    $$
 * </blockquote>
 *
 * where ${margins}_k = \vec{x}_i^T \vec{\beta}_k$.
 *
 * For optimization, we have to calculate the first derivative of the loss function, and a simple
 * calculation shows that
 *
 * <blockquote>
 *    $$
 *    \begin{align}
 *    \frac{\partial \ell(\beta, \vec{x}_i, w_i)}{\partial \beta_{j, k}}
 *    &= x_{i,j} \cdot w_i \cdot \left(\frac{e^{\vec{x}_i \cdot \vec{\beta}_k}}{\sum_{k'=0}^{K-1}
 *      e^{\vec{x}_i \cdot \vec{\beta}_{k'}}\,} - I_{y=k}\right) \\
 *    &= x_{i, j} \cdot w_i \cdot multiplier_k
 *    \end{align}
 *    $$
 * </blockquote>
 *
 * where $w_i$ is the sample weight, $I_{y=k}$ is an indicator function
 *
 *  <blockquote>
 *    $$
 *    I_{y=k} = \begin{cases}
 *          1 & y = k \\
 *          0 & else
 *       \end{cases}
 *    $$
 * </blockquote>
 *
 * and
 *
 * <blockquote>
 *    $$
 *    multiplier_k = \left(\frac{e^{\vec{x}_i \cdot \vec{\beta}_k}}{\sum_{k=0}^{K-1}
 *       e^{\vec{x}_i \cdot \vec{\beta}_k}} - I_{y=k}\right)
 *    $$
 * </blockquote>
 *
 * If any of margins is larger than 709.78, the numerical computation of multiplier and loss
 * function will suffer from arithmetic overflow. This issue occurs when there are outliers in
 * data which are far away from the hyperplane, and this will cause the failing of training once
 * infinity is introduced. Note that this is only a concern when max(margins) &gt; 0.
 *
 * Fortunately, when max(margins) = maxMargin &gt; 0, the loss function and the multiplier can
 * easily be rewritten into the following equivalent numerically stable formula.
 *
 * <blockquote>
 *    $$
 *    \ell\left(\beta, x\right) = log\left(\sum_{k=0}^{K-1} e^{margins_k - maxMargin}\right) -
 *       margins_{y} + maxMargin
 *    $$
 * </blockquote>
 *
 * Note that each term, $(margins_k - maxMargin)$ in the exponential is no greater than zero; as a
 * result, overflow will not happen with this formula.
 *
 * For $multiplier$, a similar trick can be applied as the following,
 *
 * <blockquote>
 *    $$
 *    multiplier_k = \left(\frac{e^{\vec{x}_i \cdot \vec{\beta}_k - maxMargin}}{\sum_{k'=0}^{K-1}
 *       e^{\vec{x}_i \cdot \vec{\beta}_{k'} - maxMargin}} - I_{y=k}\right)
 *    $$
 * </blockquote>
 *
 * @param bcCoefficients The broadcast coefficients corresponding to the features.
 * @param bcFeaturesStd The broadcast standard deviation values of the features.
 * @param numClasses the number of possible outcomes for k classes classification problem in
 *                   Multinomial Logistic Regression.
 * @param fitIntercept Whether to fit an intercept term.
 * @param multinomial Whether to use multinomial (softmax) or binary loss
 *
 * @note In order to avoid unnecessary computation during calculation of the gradient updates
 * we lay out the coefficients in column major order during training. This allows us to
 * perform feature standardization once, while still retaining sequential memory access
 * for speed. We convert back to row major order when we create the model,
 * since this form is optimal for the matrix operations used for prediction.
 */
private class LogisticAggregator(
    bcCoefficients: Broadcast[Vector],
    bcFeaturesStd: Broadcast[Array[Double]],
    numClasses: Int,
    fitIntercept: Boolean,
    multinomial: Boolean) extends Serializable with Logging {

  private val numFeatures = bcFeaturesStd.value.length
  private val numFeaturesPlusIntercept = if (fitIntercept) numFeatures + 1 else numFeatures
  private val coefficientSize = bcCoefficients.value.size
  private val numCoefficientSets = if (multinomial) numClasses else 1
  if (multinomial) { /* 不同类别的参数顺序拼接 */
    require(numClasses ==  coefficientSize / numFeaturesPlusIntercept, s"The number of " +
      s"coefficients should be ${numClasses * numFeaturesPlusIntercept} but was $coefficientSize")
  } else {
    require(coefficientSize == numFeaturesPlusIntercept, s"Expected $numFeaturesPlusIntercept " +
      s"coefficients but got $coefficientSize")
    require(numClasses == 1 || numClasses == 2, s"Binary logistic aggregator requires numClasses " +
      s"in {1, 2} but found $numClasses.")
  }

  private var weightSum = 0.0
  private var lossSum = 0.0 /* 损失函数 */

  private val gradientSumArray = Array.ofDim[Double](coefficientSize) /* 梯度 */

  if (multinomial && numClasses <= 2) {
    logInfo(s"Multinomial logistic regression for binary classification yields separate " +
      s"coefficients for positive and negative classes. When no regularization is applied, the" +
      s"result will be effectively the same as binary logistic regression. When regularization" +
      s"is applied, multinomial loss will produce a result different from binary loss.")
  }

  /** Update gradient and loss using binary loss function. */
  private def binaryUpdateInPlace(
      features: Vector,
      weight: Double,
      label: Double): Unit = {

    val localFeaturesStd = bcFeaturesStd.value
    val localCoefficients = bcCoefficients.value /* 特征权重系数 W */
    val localGradientArray = gradientSumArray /* 梯度 */
    /* 计算 margin: -(WX + b) */
    val margin = - {
      var sum = 0.0
      /* 计算 WX */
      features.foreachActive { (index, value) =>
        if (localFeaturesStd(index) != 0.0 && value != 0.0) {
          sum += localCoefficients(index) * value / localFeaturesStd(index) /* 特征值归一化 */
        }
      }
      /* 加上截距 */
      if (fitIntercept) sum += localCoefficients(numFeaturesPlusIntercept - 1)
      sum
    }
    /* 计算 weight * (p - y)，不同特征可以复用计算结果 */
    val multiplier = weight * (1.0 / (1.0 + math.exp(margin)) - label)
    /* 更新 W 梯度: weight * (p - y) * x */
    features.foreachActive { (index, value) =>
      if (localFeaturesStd(index) != 0.0 && value != 0.0) {
        localGradientArray(index) += multiplier * value / localFeaturesStd(index) /* 特征值归一化 */
      }
    }
    /* 更新截距梯度: weight * (p - y) */
    if (fitIntercept) {
      localGradientArray(numFeaturesPlusIntercept - 1) += multiplier
    }
    /* 累加损失: -log(p(y)) * weight */
    if (label > 0) {
      /* -log(1/(1 + exp(-(WX+b))) => log(1 + exp(margin)) */
      // The following is equivalent to log(1 + exp(margin)) but more numerically stable.
      lossSum += weight * MLUtils.log1pExp(margin)
    } else {
      /* -log(1 - 1/(1 + exp(-(WX+b))) => log(1 + exp(margin)) - margin */
      lossSum += weight * (MLUtils.log1pExp(margin) - margin)
    }
  }

  /** Update gradient and loss using multinomial (softmax) loss function. */
  private def multinomialUpdateInPlace(
      features: Vector,
      weight: Double,
      label: Double): Unit = {
    // TODO: use level 2 BLAS operations
    /*
      Note: this can still be used when numClasses = 2 for binary
      logistic regression without pivoting.
     */
    val localFeaturesStd = bcFeaturesStd.value
    val localCoefficients = bcCoefficients.value
    val localGradientArray = gradientSumArray

    // marginOfLabel is margins(label) in the formula
    var marginOfLabel = 0.0
    var maxMargin = Double.NegativeInfinity

    /* 为每个类别计算 margin: -(WX + b) */
    val margins = new Array[Double](numClasses)
    features.foreachActive { (index, value) =>
      val stdValue = value / localFeaturesStd(index) /* 特征值归一化，复用计算结果 */
      var j = 0
      while (j < numClasses) { /* 每个类目 */
        margins(j) += localCoefficients(index * numClasses + j) * stdValue
        j += 1
      }
    }
    var i = 0
    while (i < numClasses) {
      if (fitIntercept) { /* 加上截距 */
        margins(i) += localCoefficients(numClasses * numFeatures + i)
      }
      if (i == label.toInt) marginOfLabel = margins(i) /* 得到label的margin值 */
      if (margins(i) > maxMargin) { /* 获取最大margin，下面用来避免数值溢出 */
        maxMargin = margins(i)
      }
      i += 1
    }

    /**
     * When maxMargin is greater than 0, the original formula could cause overflow.
     * We address this by subtracting maxMargin from all the margins, so it's guaranteed
     * that all of the new margins will be smaller than zero to prevent arithmetic overflow.
     */
    val multipliers = new Array[Double](numClasses)
    val sum = { /* 计算分母 */
      var temp = 0.0
      var i = 0
      while (i < numClasses) {
        /* 当 maxMargin 大于0时，避免数值溢出: 分子分母同时除exp(maxMargin) */
        if (maxMargin > 0) margins(i) -= maxMargin
        val exp = math.exp(margins(i))
        temp += exp
        multipliers(i) = exp /* 计算每个类别的分子 */
        i += 1
      }
      temp
    }
    /* 计算 (p - y)，不同特征可以复用计算结果，此处可以把 weight 乘进去 */
    margins.indices.foreach { i =>
      multipliers(i) = multipliers(i) / sum - (if (label == i) 1.0 else 0.0)
    }
    /* 更新 W 梯度: weight * (p - y) * x */
    features.foreachActive { (index, value) =>
      if (localFeaturesStd(index) != 0.0 && value != 0.0) {
        val stdValue = value / localFeaturesStd(index) /* 特征值归一化，复用计算结果 */
        var j = 0
        while (j < numClasses) {
          localGradientArray(index * numClasses + j) +=
            weight * multipliers(j) * stdValue
          j += 1
        }
      }
    }
    /* 更新截距梯度: weight * (p - y) */
    if (fitIntercept) {
      var i = 0
      while (i < numClasses) {
        localGradientArray(numFeatures * numClasses + i) += weight * multipliers(i)
        i += 1
      }
    }
    /* 累加损失: -log(p(y)) * weight */
    val loss = if (maxMargin > 0) {
      /* -log(exp(marginOfLabel)/(sum*exp(maxMargin))) */
      math.log(sum) - marginOfLabel + maxMargin
    } else {
      /* -log(exp(marginOfLabel)/sum) */
      math.log(sum) - marginOfLabel
    }
    lossSum += weight * loss
  }

  /**
   * Add a new training instance to this LogisticAggregator, and update the loss and gradient
   * of the objective function.
   *
   * @param instance The instance of data point to be added.
   * @return This LogisticAggregator object.
   */
  def add(instance: Instance): this.type = { /* 迭代处理样本 */
    instance match { case Instance(label, weight, features) =>
      require(numFeatures == features.size, s"Dimensions mismatch when adding new instance." +
        s" Expecting $numFeatures but got ${features.size}.")
      require(weight >= 0.0, s"instance weight, $weight has to be >= 0.0")

      if (weight == 0.0) return this

      if (multinomial) { /* softmax */
        multinomialUpdateInPlace(features, weight, label)
      } else { /* logistic regression */
        binaryUpdateInPlace(features, weight, label)
      }
      weightSum += weight /* 累加样本weight */
      this
    }
  }

  /**
   * Merge another LogisticAggregator, and update the loss and gradient
   * of the objective function.
   * (Note that it's in place merging; as a result, `this` object will be modified.)
   *
   * @param other The other LogisticAggregator to be merged.
   * @return This LogisticAggregator object.
   */
  def merge(other: LogisticAggregator): this.type = {
    require(numFeatures == other.numFeatures, s"Dimensions mismatch when merging with another " +
      s"LeastSquaresAggregator. Expecting $numFeatures but got ${other.numFeatures}.")

    if (other.weightSum != 0.0) { /* 合并梯度和损失 */
      weightSum += other.weightSum
      lossSum += other.lossSum

      var i = 0
      val localThisGradientSumArray = this.gradientSumArray
      val localOtherGradientSumArray = other.gradientSumArray
      val len = localThisGradientSumArray.length
      while (i < len) {
        localThisGradientSumArray(i) += localOtherGradientSumArray(i)
        i += 1
      }
    }
    this
  }

  def loss: Double = { /* 获取损失函数值 */
    require(weightSum > 0.0, s"The effective number of instances should be " +
      s"greater than 0.0, but $weightSum.")
    lossSum / weightSum
  }

  def gradient: Matrix = { /* 获取梯度值 */
    require(weightSum > 0.0, s"The effective number of instances should be " +
      s"greater than 0.0, but $weightSum.")
    val result = Vectors.dense(gradientSumArray.clone())
    scal(1.0 / weightSum, result)
    /* 行 x 列: 类目数 x 特征数 */
    new DenseMatrix(numCoefficientSets, numFeaturesPlusIntercept, result.toArray)
  }
}

/**
 * LogisticCostFun implements Breeze's DiffFunction[T] for a multinomial (softmax) logistic loss
 * function, as used in multi-class classification (it is also used in binary logistic regression).
 * It returns the loss and gradient with L2 regularization at a particular point (coefficients).
 * It's used in Breeze's convex optimization routines.
 */
private class LogisticCostFun(
    instances: RDD[Instance],
    numClasses: Int,
    fitIntercept: Boolean,
    standardization: Boolean,
    bcFeaturesStd: Broadcast[Array[Double]],
    regParamL2: Double,
    multinomial: Boolean,
    aggregationDepth: Int) extends DiffFunction[BDV[Double]] {

  override def calculate(coefficients: BDV[Double]): (Double, BDV[Double]) = {
    val coeffs = Vectors.fromBreeze(coefficients)
    val bcCoeffs = instances.context.broadcast(coeffs)
    val featuresStd = bcFeaturesStd.value
    val numFeatures = featuresStd.length
    val numCoefficientSets = if (multinomial) numClasses else 1
    val numFeaturesPlusIntercept = if (fitIntercept) numFeatures + 1 else numFeatures
    /* 计算梯度 */
    val logisticAggregator = {
      val seqOp = (c: LogisticAggregator, instance: Instance) => c.add(instance)
      val combOp = (c1: LogisticAggregator, c2: LogisticAggregator) => c1.merge(c2)

      instances.treeAggregate(
        new LogisticAggregator(bcCoeffs, bcFeaturesStd, numClasses, fitIntercept,
          multinomial)
      )(seqOp, combOp, aggregationDepth)
    }
    /* 获取梯度矩阵 */
    val totalGradientMatrix = logisticAggregator.gradient
    /* 获取参数矩阵 */
    val coefMatrix = new DenseMatrix(numCoefficientSets, numFeaturesPlusIntercept, coeffs.toArray)
    // regVal is the sum of coefficients squares excluding intercept for L2 regularization.
    /* 计算正则项函数值，更新梯度 */
    val regVal = if (regParamL2 == 0.0) {
      0.0
    } else {
      var sum = 0.0
      coefMatrix.foreachActive { case (classIndex, featureIndex, value) =>
        // We do not apply regularization to the intercepts
        val isIntercept = fitIntercept && (featureIndex == numFeatures)
        if (!isIntercept) {
          // The following code will compute the loss of the regularization; also
          // the gradient of the regularization, and add back to totalGradientArray.
          sum += {
            if (standardization) {
              val gradValue = totalGradientMatrix(classIndex, featureIndex)
              totalGradientMatrix.update(classIndex, featureIndex, gradValue + regParamL2 * value) /* 更新梯度 */
              value * value /* 损失值 */
            } else {
              if (featuresStd(featureIndex) != 0.0) {
                // If `standardization` is false, we still standardize the data
                // to improve the rate of convergence; as a result, we have to
                // perform this reverse standardization by penalizing each component
                // differently to get effectively the same objective function when
                // the training dataset is not standardized.
                /* 计算经验风险会归一化特征，计算结构风险时也考虑归一化，每个特征的正则强度是不一样的。 */
                val temp = value / (featuresStd(featureIndex) * featuresStd(featureIndex))
                val gradValue = totalGradientMatrix(classIndex, featureIndex)
                totalGradientMatrix.update(classIndex, featureIndex, gradValue + regParamL2 * temp)
                value * temp
              } else {
                0.0
              }
            }
          }
        }
      }
      0.5 * regParamL2 * sum
    }
    bcCoeffs.destroy(blocking = false)

    (logisticAggregator.loss + regVal, new BDV(totalGradientMatrix.toArray))
  }
}