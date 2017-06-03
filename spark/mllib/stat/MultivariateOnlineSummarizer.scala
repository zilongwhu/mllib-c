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

package org.apache.spark.mllib.stat

import org.apache.spark.annotation.{DeveloperApi, Since}
import org.apache.spark.mllib.linalg.{Vector, Vectors}

/**
 * :: DeveloperApi ::
 * MultivariateOnlineSummarizer implements [[MultivariateStatisticalSummary]] to compute the mean,
 * variance, minimum, maximum, counts, and nonzero counts for instances in sparse or dense vector
 * format in an online fashion.
 *
 * Two MultivariateOnlineSummarizer can be merged together to have a statistical summary of
 * the corresponding joint dataset.
 *
 * A numerically stable algorithm is implemented to compute the mean and variance of instances:
 * Reference: <a href="http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance">
 * variance-wiki</a>
 * Zero elements (including explicit zero values) are skipped when calling add(),
 * to have time complexity O(nnz) instead of O(n) for each column.
 *
 * For weighted instances, the unbiased estimation of variance is defined by the reliability
 * weights:
 * see <a href="https://en.wikipedia.org/wiki/Weighted_arithmetic_mean#Reliability_weights">
 * Reliability weights (Wikipedia)</a>.
 */
@Since("1.1.0")
@DeveloperApi
class MultivariateOnlineSummarizer extends MultivariateStatisticalSummary with Serializable {

  private var n = 0
  private var currMean: Array[Double] = _
  private var currM2n: Array[Double] = _
  private var currM2: Array[Double] = _
  private var currL1: Array[Double] = _
  private var totalCnt: Long = 0
  private var totalWeightSum: Double = 0.0
  private var weightSquareSum: Double = 0.0
  private var weightSum: Array[Double] = _
  private var nnz: Array[Long] = _
  private var currMax: Array[Double] = _
  private var currMin: Array[Double] = _

  /**
   * Add a new sample to this summarizer, and update the statistical summary.
   *
   * @param sample The sample in dense/sparse vector format to be added into this summarizer.
   * @return This MultivariateOnlineSummarizer object.
   */
  @Since("1.1.0")
  def add(sample: Vector): this.type = add(sample, 1.0)

  private[spark] def add(instance: Vector, weight: Double): this.type = {
    require(weight >= 0.0, s"sample weight, ${weight} has to be >= 0.0")
    if (weight == 0.0) return this

    if (n == 0) {
      require(instance.size > 0, s"Vector should have dimension larger than zero.")
      n = instance.size /* 特征数 */

      currMean = Array.ofDim[Double](n) /* 加权均值 */
      currM2n = Array.ofDim[Double](n)
      currM2 = Array.ofDim[Double](n) /* 加权平方 */
      currL1 = Array.ofDim[Double](n) /* 加权绝对值 */
      weightSum = Array.ofDim[Double](n) /* 累计权重 */
      nnz = Array.ofDim[Long](n) /* 非零数 */
      currMax = Array.fill[Double](n)(Double.MinValue) /* 最大值 */
      currMin = Array.fill[Double](n)(Double.MaxValue) /* 最小值 */
    }

    require(n == instance.size, s"Dimensions mismatch when adding new sample." +
      s" Expecting $n but got ${instance.size}.")

    val localCurrMean = currMean
    val localCurrM2n = currM2n
    val localCurrM2 = currM2
    val localCurrL1 = currL1
    val localWeightSum = weightSum
    val localNumNonzeros = nnz
    val localCurrMax = currMax
    val localCurrMin = currMin
    instance.foreachActive { (index, value) =>
      if (value != 0.0) {
        if (localCurrMax(index) < value) { /* 更新最大值 */
          localCurrMax(index) = value
        }
        if (localCurrMin(index) > value) { /* 更新最小值 */
          localCurrMin(index) = value
        }

        /* 更新均值: (preWeightSum*prevMean + weight*value)/(preWeightSum + weight) */
        val prevMean = localCurrMean(index)
        val diff = value - prevMean
        localCurrMean(index) = prevMean + weight * diff / (localWeightSum(index) + weight)
        /* 公式请见：http://blog.csdn.net/zgwhugr0216/article/details/72851761 */
        localCurrM2n(index) += weight * (value - localCurrMean(index)) * diff
        localCurrM2(index) += weight * value * value
        localCurrL1(index) += weight * math.abs(value) /* weight * |value| */

        localWeightSum(index) += weight /* 特征累计权重 */
        localNumNonzeros(index) += 1 /* 特征计数(!=0) */
      }
    }

    totalWeightSum += weight /* 样本累计权重 */
    weightSquareSum += weight * weight /* 累计平方权重 */
    totalCnt += 1 /* 总样本数 */
    this
  }

  /**
   * Merge another MultivariateOnlineSummarizer, and update the statistical summary.
   * (Note that it's in place merging; as a result, `this` object will be modified.)
   *
   * @param other The other MultivariateOnlineSummarizer to be merged.
   * @return This MultivariateOnlineSummarizer object.
   */
  @Since("1.1.0")
  def merge(other: MultivariateOnlineSummarizer): this.type = {
    if (this.totalWeightSum != 0.0 && other.totalWeightSum != 0.0) {
      require(n == other.n, s"Dimensions mismatch when merging with another summarizer. " +
        s"Expecting $n but got ${other.n}.")
      totalCnt += other.totalCnt
      totalWeightSum += other.totalWeightSum
      weightSquareSum += other.weightSquareSum
      var i = 0
      while (i < n) { /* 迭代特征 */
        val thisNnz = weightSum(i)
        val otherNnz = other.weightSum(i)
        val totalNnz = thisNnz + otherNnz /* 特征权重 */
        val totalCnnz = nnz(i) + other.nnz(i) /* 特征计数 */
        if (totalNnz != 0.0) {
          /* 均值: (thisNnz*thisMean + otherNnz*otherMean)/(thisNnz + otherNnz) */
          val deltaMean = other.currMean(i) - currMean(i)
          // merge mean together
          currMean(i) += deltaMean * otherNnz / totalNnz
          /* 公式请见：http://blog.csdn.net/zgwhugr0216/article/details/72851761 */
          // merge m2n together
          currM2n(i) += other.currM2n(i) + deltaMean * deltaMean * thisNnz * otherNnz / totalNnz
          // merge m2 together
          currM2(i) += other.currM2(i)
          // merge l1 together
          currL1(i) += other.currL1(i)
          // merge max and min
          currMax(i) = math.max(currMax(i), other.currMax(i))
          currMin(i) = math.min(currMin(i), other.currMin(i))
        }
        weightSum(i) = totalNnz
        nnz(i) = totalCnnz
        i += 1
      }
    } else if (totalWeightSum == 0.0 && other.totalWeightSum != 0.0) {
      this.n = other.n
      this.currMean = other.currMean.clone()
      this.currM2n = other.currM2n.clone()
      this.currM2 = other.currM2.clone()
      this.currL1 = other.currL1.clone()
      this.totalCnt = other.totalCnt
      this.totalWeightSum = other.totalWeightSum
      this.weightSquareSum = other.weightSquareSum
      this.weightSum = other.weightSum.clone()
      this.nnz = other.nnz.clone()
      this.currMax = other.currMax.clone()
      this.currMin = other.currMin.clone()
    }
    this
  }

  /**
   * Sample mean of each dimension.
   *
   */
  @Since("1.1.0")
  override def mean: Vector = { /* 获取特征均值 */
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    val realMean = Array.ofDim[Double](n)
    var i = 0
    while (i < n) {
      /* 和特征值为0(缺失默认为0)的样本合并 */
      realMean(i) = currMean(i) * (weightSum(i) / totalWeightSum)
      i += 1
    }
    Vectors.dense(realMean)
  }

  /**
   * Unbiased estimate of sample variance of each dimension.
   *
   */
  @Since("1.1.0")
  override def variance: Vector = { /* 方差 */
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    val realVariance = Array.ofDim[Double](n)
    /* 样本方差: Weighted incremental algorithm */
    val denominator = totalWeightSum - (weightSquareSum / totalWeightSum)

    // Sample variance is computed, if the denominator is less than 0, the variance is just 0.
    if (denominator > 0.0) {
      val deltaMean = currMean
      var i = 0
      val len = currM2n.length
      while (i < len) {
        /* 公式请见：http://blog.csdn.net/zgwhugr0216/article/details/72851761 */
        realVariance(i) = (currM2n(i) + deltaMean(i) * deltaMean(i) * weightSum(i) *
          (totalWeightSum - weightSum(i)) / totalWeightSum) / denominator
        i += 1
      }
    }
    Vectors.dense(realVariance)
  }

  /**
   * Sample size.
   *
   */
  @Since("1.1.0")
  override def count: Long = totalCnt

  /**
   * Number of nonzero elements in each dimension.
   *
   */
  @Since("1.1.0")
  override def numNonzeros: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")

    Vectors.dense(nnz.map(_.toDouble))
  }

  /**
   * Maximum value of each dimension.
   *
   */
  @Since("1.1.0")
  override def max: Vector = {
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    var i = 0
    while (i < n) {
      if ((nnz(i) < totalCnt) && (currMax(i) < 0.0)) currMax(i) = 0.0
      i += 1
    }
    Vectors.dense(currMax)
  }

  /**
   * Minimum value of each dimension.
   *
   */
  @Since("1.1.0")
  override def min: Vector = {
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    var i = 0
    while (i < n) {
      if ((nnz(i) < totalCnt) && (currMin(i) > 0.0)) currMin(i) = 0.0
      i += 1
    }
    Vectors.dense(currMin)
  }

  /**
   * L2 (Euclidian) norm of each dimension.
   *
   */
  @Since("1.2.0")
  override def normL2: Vector = { /* 二阶范式 */
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    val realMagnitude = Array.ofDim[Double](n)

    var i = 0
    val len = currM2.length
    while (i < len) {
      realMagnitude(i) = math.sqrt(currM2(i))
      i += 1
    }
    Vectors.dense(realMagnitude)
  }

  /**
   * L1 norm of each dimension.
   *
   */
  @Since("1.2.0")
  override def normL1: Vector = { /* 一阶范式 */
    require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

    Vectors.dense(currL1)
  }
}
