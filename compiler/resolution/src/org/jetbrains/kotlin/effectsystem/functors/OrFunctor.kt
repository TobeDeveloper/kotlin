/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.effectsystem.functors

import org.jetbrains.kotlin.effectsystem.effects.ESReturns
import org.jetbrains.kotlin.effectsystem.factories.ClausesFactory
import org.jetbrains.kotlin.effectsystem.factories.lift
import org.jetbrains.kotlin.effectsystem.impls.and
import org.jetbrains.kotlin.effectsystem.impls.or
import org.jetbrains.kotlin.effectsystem.structure.ESClause

class OrFunctor : AbstractSequentialBinaryFunctor() {
    override fun combineClauses(left: List<ESClause>, right: List<ESClause>): List<ESClause> {
        /* Normally, `left` and `right` contain clauses that end with Returns(false/true), but if
         expression wasn't properly typechecked, we could get some senseless clauses here, e.g.
         with Returns(1) (note that they still *return* as guaranteed by AbstractSequentialBinaryFunctor).
         We will just ignore such clauses in order to make smartcasting robust while typing */

        val (leftTrue, leftFalse) = left.partitionByOutcome(ESReturns(true.lift()), ESReturns(false.lift()))
        val (rightTrue, rightFalse) = right.partitionByOutcome(ESReturns(true.lift()), ESReturns(false.lift()))

        val leftTrueFolded = foldConditionsWithOr(leftTrue)
        val rightTrueFolded = foldConditionsWithOr(rightTrue)
        val leftFalseFolded = foldConditionsWithOr(leftFalse)
        val rightFalseFolded = foldConditionsWithOr(rightFalse)

        val returnsTrue = ClausesFactory.create(
                premise = leftTrueFolded.or(rightTrueFolded),
                conclusion = ESReturns(true.lift())
        )

        val returnsFalse = ClausesFactory.create(
                premise = leftFalseFolded.and(rightFalseFolded),
                conclusion = ESReturns(false.lift())
        )

        return listOf(returnsTrue, returnsFalse)
    }
}