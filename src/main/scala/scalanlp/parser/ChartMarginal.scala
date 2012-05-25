package scalanlp.parser

/**
 * Holds the information for the marginals for a sentence
 *
 * @param scorer the specialization for a sentence.
 * @param inside inside chart
 * @param outside outside chart
 * @param partition the normalization constant aka inside score of the root aka probability of the sentence
 * @tparam Chart The kind of parse chart
 * @tparam L the label type
 * @tparam W the word type
 */
case class ChartMarginal[+Chart[X]<:ParseChart[X], L, W](scorer: AugmentedAnchoring[L, W],
                                                         inside: Chart[L], outside: Chart[L], partition: Double) extends Marginal[L, W] {


  /**
   * Forest traversal that visits spans in a "bottom up" order.
   */
  def visitPostorder(spanVisitor: AnchoredVisitor[L]) {
    if(partition.isInfinite) throw new RuntimeException("No parse for " + words)
    val itop = inside.top

    // handle lexical
    for (i <- 0 until words.length) {
      for {
        aa <- lexicon.tagsForWord(words(i))
        a = grammar.labelIndex(aa)
        ref <- scorer.refined.validLabelRefinements(i, i+ 1, a)
      } {
        val score:Double = scorer.scoreSpan(i, i+1, a, ref) + outside.bot(i, i+1, a, ref) - partition
        if (score != Double.NegativeInfinity) {
          //println(scorer.scoreSpan(i, i+1, a, ref), outside.bot(i, i+1, a, ref), partition)
          spanVisitor.visitSpan(i, i+1, a, ref, math.exp(score))
        }
      }
    }


    // handle binaries
    for {
      span <- 2 to inside.length
      begin <- 0 to (inside.length - span)
    } {
      val end = begin + span

      // I get a 20% speedup if i inline these arrays. so be it.
      val narrowRight = inside.top.narrowRight(begin)
      val narrowLeft = inside.top.narrowLeft(end)
      val wideRight = inside.top.wideRight(begin)
      val wideLeft = inside.top.wideLeft(end)

      val coarseNarrowRight = inside.top.coarseNarrowRight(begin)
      val coarseNarrowLeft = inside.top.coarseNarrowLeft(end)
      val coarseWideRight = inside.top.coarseWideRight(begin)
      val coarseWideLeft = inside.top.coarseWideLeft(end)

      for (a <- inside.bot.enteredLabelIndexes(begin, end); refA <- inside.bot.enteredLabelRefinements(begin, end, a)) {
        var i = 0
        val rules = grammar.indexedBinaryRulesWithParent(a)
        val spanScore = scorer.scoreSpan(begin, end, a, refA)
        val aScore = outside.bot.labelScore(begin, end, a, refA) + spanScore
        var count = 0.0
        if (!aScore.isInfinite)
          while(i < rules.length) {
            val r = rules(i)
            val b = grammar.leftChild(r)
            val c = grammar.rightChild(r)
            i += 1

            val narrowR:Int = coarseNarrowRight(b)
            val narrowL:Int = coarseNarrowLeft(c)

            val canBuildThisRule = if (narrowR >= end || narrowL < narrowR) {
              false
            } else {
              val trueX:Int = coarseWideLeft(c)
              val trueMin = if(narrowR > trueX) narrowR else trueX
              val wr:Int = coarseWideRight(b)
              val trueMax = if(wr < narrowL) wr else narrowL
              if(trueMin > narrowL || trueMin > trueMax) false
              else trueMin < trueMax + 1
            }

            if(canBuildThisRule) {


              // initialize core scores


              val refinements = scorer.refined.validRuleRefinementsGivenParent(begin, end, r, refA)
              var ruleRefIndex = 0
              while(ruleRefIndex < refinements.length) {
                val refR = refinements(ruleRefIndex)
                ruleRefIndex += 1
                val refB = scorer.refined.leftChildRefinement(r, refR)
                val refC = scorer.refined.rightChildRefinement(r, refR)
                val narrowR:Int = narrowRight(b)(refB)
                val narrowL:Int = narrowLeft(c)(refC)

                val feasibleSpan = if (narrowR >= end || narrowL < narrowR) {
                  0L
                } else {
                  val trueX:Int = wideLeft(c)(refC)
                  val trueMin = if (narrowR > trueX) narrowR else trueX
                  val wr:Int = wideRight(b)(refB)
                  val trueMax = if (wr < narrowL) wr else narrowL
                  if (trueMin > narrowL || trueMin > trueMax)  0L
                  else ((trueMin:Long) << 32) | ((trueMax + 1):Long)
                }
                var split = (feasibleSpan >> 32).toInt
                val endSplit = feasibleSpan.toInt // lower 32 bits
                while(split < endSplit) {
                  val bInside = itop.labelScore(begin, split, b, refB)
                  val cInside = itop.labelScore(split, end, c, refC)
                  if (!java.lang.Double.isInfinite(bInside + cInside)) {
                    val ruleScore = scorer.scoreBinaryRule(begin, split, end, r, refR)
                    val score = aScore + ruleScore + bInside + cInside - partition
                    val expScore = math.exp(score)
                    count += expScore
                    spanVisitor.visitBinaryRule(begin, split, end, r, refR, expScore)
                  }

                  split += 1
                }
              }
            }
          }
        spanVisitor.visitSpan(begin, end, a, refA, count)
      }
    }

    // Unaries
    for {
      span <- 1 to words.length
      begin <- 0 to (words.length - span)
      end = begin + span
      a <- inside.top.enteredLabelIndexes(begin, end)
      refA <- inside.top.enteredLabelRefinements(begin, end, a)
    } {
      val aScore = outside.top.labelScore(begin, end, a, refA)
      for (r <- grammar.indexedUnaryRulesWithParent(a); refR <- scorer.refined.validRuleRefinementsGivenParent(begin, end, r, refA)) {
        val b = grammar.child(r)
        val refB = scorer.refined.childRefinement(r, refR)
        val bScore = inside.bot.labelScore(begin, end, b, refB)
        val rScore = scorer.scoreUnaryRule(begin, end, r, refR)
        val prob = math.exp(bScore + aScore + rScore - partition)
        if (prob > 0)
          spanVisitor.visitUnaryRule(begin, end, r, refR, prob)
      }
    }
  }

  def withCharts[Chart2[X] <: ParseChart[X]](factory: ParseChart.Factory[Chart2]) = {
    ChartMarginal(scorer, scorer.words, factory)
  }

}

object ChartMarginal {
  def apply[L, W, Chart[X] <: ParseChart[X]](grammar: AugmentedGrammar[L, W],
                                             sent: Seq[W],
                                             chartFactory: ParseChart.Factory[Chart]): ChartMarginal[Chart, L, W] = {
    apply(grammar.specialize(sent), sent, chartFactory)
  }

  def apply[L, W, Chart[X] <: ParseChart[X]](scorer: AugmentedAnchoring[L, W],
                                             sent: Seq[W],
                                             chartFactory: ParseChart.Factory[Chart]): ChartMarginal[Chart, L, W] = {
    val inside = buildInsideChart(scorer, sent, chartFactory)
    val outside = buildOutsideChart(scorer, inside, chartFactory)
    val partition = rootScore(scorer, inside)
    ChartMarginal(scorer, inside, outside, partition)
  }


  private def rootScore[L, W](scorer: AugmentedAnchoring[L, W], inside: ParseChart[L]): Double = {
    val rootIndex: Int = scorer.grammar.labelIndex(scorer.grammar.root)
    val rootScores = new Array[Double](scorer.refined.validLabelRefinements(0, inside.length, rootIndex).length)
    var offset = 0
    for(ref <- inside.top.enteredLabelRefinements(0, inside.length, rootIndex)) {
      val score = inside.top.labelScore(0, inside.length, rootIndex, ref)
      if(score != Double.NegativeInfinity) {
        rootScores(offset) = score
        offset += 1
      }
    }
    inside.sum(rootScores, offset)
  }

  private def buildInsideChart[L, W, Chart[X] <: ParseChart[X]](anchoring: AugmentedAnchoring[L, W],
                                                                words: Seq[W],
                                                                chartFactory: ParseChart.Factory[Chart]): Chart[L] = {
    val refined = anchoring.refined
    val core = anchoring.core

    val grammar = anchoring.grammar
    val lexicon = anchoring.lexicon

    val inside = chartFactory(grammar.labelIndex,
      Array.tabulate(grammar.labelIndex.size)(refined.numValidRefinements),
      words.length)
    for{i <- 0 until words.length} {
      var foundSomething = false
      for {
        aa <- lexicon.tagsForWord(words(i))
        a = grammar.labelIndex(aa)
        coreScore = core.scoreSpan(i, i+1, a) if coreScore != Double.NegativeInfinity
        ref <- refined.validLabelRefinements(i, i+1, a)
      } {
        val score:Double = refined.scoreSpan(i, i+1, a, ref) + coreScore
        if (score != Double.NegativeInfinity) {
          inside.bot.enter(i, i+1, a, ref, score)
          foundSomething = true
        }
      }

      updateInsideUnaries(inside, anchoring,  i, i+1)
    }


    // buffer. Set to 1000. If we ever fill it up, accumulate everything into
    // elem 0 and try again
    val scoreArray = new Array[Double](1000)

    val top = inside.top
    val g = grammar

    // a -> bc over [begin, split, end)
    for {
      span <- 2 to words.length
      begin <- 0 to (words.length - span)
      end = begin + span
    } {
      // I get a 20% speedup by inlining code dealing with these arrays. sigh.
      val narrowRight = top.narrowRight(begin)
      val narrowLeft = top.narrowLeft(end)
      val wideRight = top.wideRight(begin)
      val wideLeft = top.wideLeft(end)

      val coarseNarrowRight = top.coarseNarrowRight(begin)
      val coarseNarrowLeft = top.coarseNarrowLeft(end)
      val coarseWideRight = top.coarseWideRight(begin)
      val coarseWideLeft = top.coarseWideLeft(end)

      for {
        a <- 0 until grammar.labelIndex.size
        coreSpan = core.scoreSpan(begin, end, a) if coreSpan != Double.NegativeInfinity
        refA <- refined.validLabelRefinements(begin, end, a)
      } {
        val passScore = refined.scoreSpan(begin, end, a, refA) + coreSpan
        var offset = 0 // into scoreArray
        if(!passScore.isInfinite) {
          var ruleIndex = 0
          // into rules
          val rules = g.indexedBinaryRulesWithParent(a)
          while(ruleIndex < rules.length) {
            val r = rules(ruleIndex)
            val b = g.leftChild(r)
            val c = g.rightChild(r)
            ruleIndex += 1

            // Check: can we build any refinement of this rule?
            // basically, we can if
            val narrowR:Int = coarseNarrowRight(b)
            val narrowL:Int = coarseNarrowLeft(c)

            val canBuildThisRule = if (narrowR >= end || narrowL < narrowR) {
              false
            } else {
              val trueX:Int = coarseWideLeft(c)
              val trueMin = if(narrowR > trueX) narrowR else trueX
              val wr:Int = coarseWideRight(b)
              val trueMax = if(wr < narrowL) wr else narrowL
              if(trueMin > narrowL || trueMin > trueMax) false
              else trueMin < trueMax + 1
            }

            if(canBuildThisRule) {
              val refinements = refined.validRuleRefinementsGivenParent(begin, end, r, refA)
              var ruleRefIndex = 0
              while(ruleRefIndex < refinements.length) {
                val refR = refinements(ruleRefIndex)
                ruleRefIndex += 1
                val refB = refined.leftChildRefinement(r, refR)
                val refC = refined.rightChildRefinement(r, refR)
                // narrowR etc is hard to understand, and should be a different methood
                // but caching the arrays speeds things up by 20% or more...
                // so it's inlined.
                //
                // See [[ParseChart]] for what these labels mean
                val narrowR:Int = narrowRight(b)(refB)
                val narrowL:Int = narrowLeft(c)(refC)

                val feasibleSpan = if (narrowR >= end || narrowL < narrowR) {
                  0L
                } else {
                  val trueX:Int = wideLeft(c)(refC)
                  val trueMin = if(narrowR > trueX) narrowR else trueX
                  val wr:Int = wideRight(b)(refB)
                  val trueMax = if(wr < narrowL) wr else narrowL
                  if(trueMin > narrowL || trueMin > trueMax)  0L
                  else ((trueMin:Long) << 32) | ((trueMax + 1):Long)
                }
                var split = (feasibleSpan >> 32).toInt
                val endSplit = feasibleSpan.toInt // lower 32 bits
                while(split < endSplit) {
                  val bScore = inside.top.labelScore(begin, split, b, refB)
                  val cScore = inside.top.labelScore(split, end, c, refC)
                  val withoutRule = bScore + cScore + passScore
                  if(withoutRule != Double.NegativeInfinity) {

                    val prob = (
                      withoutRule
                        + (refined.scoreBinaryRule(begin, split, end, r, refR)
                        + core.scoreBinaryRule(begin, split, end, r))
                      )

                    if(!java.lang.Double.isInfinite(prob)) {
                      scoreArray(offset) = prob
                      offset += 1
                      // buffer full
                      if(offset == scoreArray.length) {
                        scoreArray(0) = inside.sum(scoreArray, offset)
                        offset = 1
                      }
                    }
                  }
                  split += 1
                }
              }
            }
          }
        }
        // done updating vector, do an enter:
        if(offset > 0) {
          inside.bot.enterSum(begin, end, a, refA, scoreArray, offset)
        }

      }
      updateInsideUnaries(inside, anchoring, begin, end)
    }
    inside
  }


  private def buildOutsideChart[L, W, Chart[X] <: ParseChart[X]](anchoring: AugmentedAnchoring[L, W],
                                                                 inside: Chart[L],
                                                                 chartFactory: ParseChart.Factory[Chart]):Chart[L] = {
    val refined = anchoring.refined
    val core = anchoring.core

    val grammar = anchoring.grammar
    val rootIndex = grammar.labelIndex(grammar.root)

    val length = inside.length
    val outside = chartFactory(grammar.labelIndex, Array.tabulate(grammar.labelIndex.size)(refined.numValidRefinements), length)
    for(refRoot <- refined.validLabelRefinements(0, inside.length, rootIndex)) {
      outside.top.enter(0, inside.length, rootIndex, refRoot, 0.0)
    }
    val itop = inside.top
    for {
      span <- length until 0 by (-1)
      begin <- 0 to (length-span)
    } {
      val end = begin + span
      val narrowRight = itop.narrowRight(begin)
      val narrowLeft = itop.narrowLeft(end)
      val wideRight = itop.wideRight(begin)
      val wideLeft = itop.wideLeft(end)

      val coarseNarrowRight = inside.top.coarseNarrowRight(begin)
      val coarseNarrowLeft = inside.top.coarseNarrowLeft(end)
      val coarseWideRight = inside.top.coarseWideRight(begin)
      val coarseWideLeft = inside.top.coarseWideLeft(end)

      updateOutsideUnaries(outside, inside, anchoring, begin, end)
      if(span > 1)
      // a ->  bc  [begin, split, end)
        for ( a <- outside.bot.enteredLabelIndexes(begin, end);
              refA <- outside.bot.enteredLabelRefinements(begin, end, a) ) {
          val coreScore = core.scoreSpan(begin, end, a)
          val aScore:Double = outside.bot.labelScore(begin, end, a, refA) + refined.scoreSpan(begin, end, a, refA) + coreScore
          if (!aScore.isInfinite) {
            val rules = grammar.indexedBinaryRulesWithParent(a)
            var br = 0
            while(br < rules.length) {
              val r = rules(br)
              val b = grammar.leftChild(r)
              val c = grammar.rightChild(r)
              br += 1

              // can I possibly build any refinement of this rule?
              val narrowR:Int = coarseNarrowRight(b)
              val narrowL:Int = coarseNarrowLeft(c)

              val canBuildThisRule = if (narrowR >= end || narrowL < narrowR) {
                false
              } else {
                val trueX:Int = coarseWideLeft(c)
                val trueMin = if(narrowR > trueX) narrowR else trueX
                val wr:Int = coarseWideRight(b)
                val trueMax = if(wr < narrowL) wr else narrowL
                if(trueMin > narrowL || trueMin > trueMax) false
                else trueMin < trueMax + 1
              }

              if(canBuildThisRule) {
                for(refR <- refined.validRuleRefinementsGivenParent(begin, end, r, refA)) {
                  val refB = refined.leftChildRefinement(r, refR)
                  val refC = refined.rightChildRefinement(r, refR)
                  val narrowR:Int = narrowRight(b)(refB)
                  val narrowL:Int = narrowLeft(c)(refC)

                  // this is too slow, so i'm having to inline it.
                  //              val feasibleSpan = itop.feasibleSpanX(begin, end, b, c)
                  val feasibleSpan = if (narrowR >= end || narrowL < narrowR) {
                    0L
                  } else {
                    val trueX:Int = wideLeft(c)(refC)
                    val trueMin = if(narrowR > trueX) narrowR else trueX
                    val wr:Int = wideRight(b)(refB)
                    val trueMax = if(wr < narrowL) wr else narrowL
                    if(trueMin > narrowL || trueMin > trueMax)  0L
                    else ((trueMin:Long) << 32) | ((trueMax + 1):Long)
                  }

                  var split = (feasibleSpan >> 32).toInt
                  val endSplit = feasibleSpan.toInt // lower 32 bits

                  while(split < endSplit) {
                    val bInside = itop.labelScore(begin, split, b, refB)
                    val cInside = itop.labelScore(split, end, c, refC)
                    if (bInside != Double.NegativeInfinity && cInside != Double.NegativeInfinity && aScore != Double.NegativeInfinity) {
                      val ruleScore = refined.scoreBinaryRule(begin, split, end, r, refR) + core.scoreBinaryRule(begin, split, end, r)
                      val score = aScore + ruleScore
                      val bOutside = score + cInside
                      val cOutside = score + bInside
                      outside.top.enter(begin, split, b, refB, bOutside)
                      outside.top.enter(split, end, c, refC, cOutside)
                    }

                    split += 1
                  }
                }
              }
            }
          }
        }
    }
    outside
  }


  private def updateInsideUnaries[L, W](chart: ParseChart[L],
                                        anchoring: AugmentedAnchoring[L, W],
                                        begin: Int, end: Int) = {
    val refined = anchoring.refined
    val core = anchoring.core
    val grammar = anchoring.grammar
    for(bi <- chart.bot.enteredLabelIndexes(begin, end); refB <- chart.bot.enteredLabelRefinements(begin, end, bi)) {
      val b = bi
      val bScore = chart.bot.labelScore(begin, end, b, refB)
      val rules = grammar.indexedUnaryRulesWithChild(b)
      var j = 0
      while(j < rules.length) {
        val r = rules(j)
        val coreScore = core.scoreUnaryRule(begin, end, r)
        if(coreScore != Double.NegativeInfinity) {
          val a = grammar.parent(r)
          for(refR <- refined.validUnaryRuleRefinementsGivenChild(begin, end, r, refB)) {
            val refA = refined.parentRefinement(r, refR)
            val ruleScore: Double = refined.scoreUnaryRule(begin, end, r, refR) + coreScore
            val prob: Double = bScore + ruleScore
            if(prob != Double.NegativeInfinity) {
              chart.top.enter(begin, end, a, refA, prob)
            }
          }
        }
        j += 1
      }
    }

  }

  private def updateOutsideUnaries[L, W](chart: ParseChart[L],
                                         inside: ParseChart[L],
                                         anchoring: AugmentedAnchoring[L, W],
                                         begin: Int, end: Int) = {
    val refined = anchoring.refined
    val core = anchoring.core
    val grammar = anchoring.grammar
    for(ai <- chart.top.enteredLabelIndexes(begin, end); refA <- chart.top.enteredLabelRefinements(begin, end, ai)) {
      val a = ai
      val bScore = chart.top.labelScore(begin, end, a, refA)
      val rules = grammar.indexedUnaryRulesWithParent(a)
      var j = 0
      while(j < rules.length) {
        val r = rules(j)
        val coreScore = core.scoreUnaryRule(begin, end, r)
        if(coreScore != Double.NegativeInfinity) {
          val b = grammar.child(r)
          for(refR <- refined.validRuleRefinementsGivenParent(begin, end, rules(j), refA)) {
            val refB = refined.childRefinement(rules(j), refR)
            val ruleScore: Double = refined.scoreUnaryRule(begin, end, rules(j), refR) + coreScore
            val prob: Double = bScore + ruleScore
            if(prob != Double.NegativeInfinity) {
              chart.bot.enter(begin, end, b, refB, prob)
            }
          }
        }
        j += 1
      }
    }

  }
}
