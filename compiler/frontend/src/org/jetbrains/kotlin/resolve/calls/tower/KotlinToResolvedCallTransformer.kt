/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.ArgumentTypeResolver
import org.jetbrains.kotlin.resolve.calls.DiagnosticReporterByTrackingStrategy
import org.jetbrains.kotlin.resolve.calls.REPORT_MISSING_NEW_INFERENCE_DIAGNOSTIC
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.getEffectiveExpectedType
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.callUtil.isFakeElement
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.components.isVararg
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.CallPosition
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.DataFlowAnalyzer
import java.util.*


class KotlinToResolvedCallTransformer(
        private val callCheckers: Iterable<CallChecker>,
        private val languageFeatureSettings: LanguageVersionSettings,
        private val dataFlowAnalyzer: DataFlowAnalyzer,
        private val argumentTypeResolver: ArgumentTypeResolver,
        private val constantExpressionEvaluator: ConstantExpressionEvaluator
) {

    fun <D : CallableDescriptor> transformAndReport(
            baseResolvedCall: ResolvedKotlinCall,
            context: BasicCallResolutionContext,
            trace: BindingTrace? // if trace is not null then all information will be reported to this trace
    ): ResolvedCall<D> {
        if (baseResolvedCall is ResolvedKotlinCall.CompletedResolvedKotlinCall) {
            val allResolvedCalls = baseResolvedCall.allInnerCalls.mapTo(ArrayList<ResolvedCall<*>>()) { transformAndReportCompletedCall<CallableDescriptor>(it, context, trace) }
            val result = transformAndReportCompletedCall<D>(baseResolvedCall.completedCall, context, trace)
            allResolvedCalls.add(result)

            if (trace != null) {
                val callCheckerContext = CallCheckerContext(context.replaceBindingTrace(trace), languageFeatureSettings)
                for (resolvedCall in allResolvedCalls) {
                    runCallCheckers(resolvedCall, callCheckerContext)
                }
            }

            return result
        }

        val onlyResolvedCall = (baseResolvedCall as ResolvedKotlinCall.OnlyResolvedKotlinCall)
        trace?.record(BindingContext.ONLY_RESOLVED_CALL, onlyResolvedCall.candidate.kotlinCall.psiKotlinCall.psiCall, onlyResolvedCall)

        return createStubResolvedCallAndWriteItToTrace(onlyResolvedCall.candidate, trace)
    }

    fun <D : CallableDescriptor> createStubResolvedCallAndWriteItToTrace(candidate: KotlinResolutionCandidate, trace: BindingTrace?): ResolvedCall<D> {
        val result = when (candidate) {
            is VariableAsFunctionKotlinResolutionCandidate -> {
                val variableStub = StubOnlyResolvedCall<VariableDescriptor>(candidate.resolvedVariable)
                val invokeStub = StubOnlyResolvedCall<FunctionDescriptor>(candidate.invokeCandidate)
                StubOnlyVariableAsFunctionCall(variableStub, invokeStub) as ResolvedCall<D>
            }
            is SimpleKotlinResolutionCandidate -> {
                StubOnlyResolvedCall<D>(candidate)
            }
        }
        if (trace != null) {
            val tracing = candidate.kotlinCall.psiKotlinCall.tracingStrategy

            tracing.bindReference(trace, result)
            tracing.bindResolvedCall(trace, result)
        }
        return result
    }


    private fun <D : CallableDescriptor> transformAndReportCompletedCall(
            completedCall: CompletedKotlinCall,
            context: BasicCallResolutionContext,
            trace: BindingTrace?
    ): ResolvedCall<D> {
        fun <C> C.runIfTraceNotNull(action: (BasicCallResolutionContext, BindingTrace, C) -> Unit): C {
            if (trace != null) action(context, trace, this)
            return this
        }

        return when (completedCall) {
            is CompletedKotlinCall.Simple -> {
                NewResolvedCallImpl<D>(completedCall).runIfTraceNotNull(this::bindResolvedCall).runIfTraceNotNull(this::runArgumentsChecks)
            }
            is CompletedKotlinCall.VariableAsFunction -> {
                val resolvedCall = NewVariableAsFunctionResolvedCallImpl(
                        completedCall,
                        NewResolvedCallImpl(completedCall.variableCall),
                        NewResolvedCallImpl<FunctionDescriptor>(completedCall.invokeCall).runIfTraceNotNull(this::runArgumentsChecks)
                ).runIfTraceNotNull(this::bindResolvedCall)

                @Suppress("UNCHECKED_CAST")
                (resolvedCall as ResolvedCall<D>)
            }
        }
    }

    private fun runCallCheckers(resolvedCall: ResolvedCall<*>, callCheckerContext: CallCheckerContext) {
        val calleeExpression = if (resolvedCall is VariableAsFunctionResolvedCall)
            resolvedCall.variableCall.call.calleeExpression
        else
            resolvedCall.call.calleeExpression
        val reportOn =
                if (calleeExpression != null && !calleeExpression.isFakeElement) calleeExpression
                else resolvedCall.call.callElement

        for (callChecker in callCheckers) {
            callChecker.check(resolvedCall, reportOn, callCheckerContext)

            if (resolvedCall is VariableAsFunctionResolvedCall) {
                callChecker.check(resolvedCall.variableCall, reportOn, callCheckerContext)
            }
        }
    }


    // todo very beginning code
    private fun runArgumentsChecks(
            context: BasicCallResolutionContext,
            trace: BindingTrace,
            resolvedCall: NewResolvedCallImpl<*>
    ) {

        for (valueArgument in resolvedCall.call.valueArguments) {
            val argumentMapping = resolvedCall.getArgumentMapping(valueArgument!!)
            val (expectedType, callPosition) = when (argumentMapping) {
                is ArgumentMatch -> Pair(
                        getEffectiveExpectedType(argumentMapping.valueParameter, valueArgument),
                        CallPosition.ValueArgumentPosition(resolvedCall, argumentMapping.valueParameter, valueArgument))
                else -> Pair(TypeUtils.NO_EXPECTED_TYPE, CallPosition.Unknown)
            }
            val newContext =
                    context.replaceDataFlowInfo(resolvedCall.dataFlowInfoForArguments.getInfo(valueArgument))
                            .replaceExpectedType(expectedType)
                            .replaceCallPosition(callPosition)
                            .replaceBindingTrace(trace)

            // todo
//            if (valueArgument.isExternal()) continue

            val argumentExpression = valueArgument.getArgumentExpression() ?: continue
            val deparenthesized = argumentExpression.let {
                KtPsiUtil.getLastElementDeparenthesized(it, context.statementFilter)
            } ?: continue

            val recordedType = context.trace.getType(deparenthesized)
            var updatedType = recordedType

            val resolvedCall = deparenthesized.getResolvedCall(trace.bindingContext)
            if (resolvedCall != null) {
                updatedType = resolvedCall.resultingDescriptor.returnType ?: updatedType
            }

            // For the cases like 'foo(1)' the type of '1' depends on expected type (it can be Int, Byte, etc.),
            // so while the expected type is not known, it's IntegerValueType(1), and should be updated when the expected type is known.
            if (recordedType != null && !recordedType.constructor.isDenotable) {
                updatedType = argumentTypeResolver.updateResultArgumentTypeIfNotDenotable(newContext, deparenthesized) ?: updatedType
            }

            updatedType = updateRecordedTypeForArgument(updatedType, recordedType, argumentExpression, context)

//            dataFlowAnalyzer.checkType(updatedType, deparenthesized, newContext)
        }

    }

    // See CallCompleter#updateRecordedTypeForArgument
    private fun updateRecordedTypeForArgument(
            updatedType: KotlinType?,
            recordedType: KotlinType?,
            argumentExpression: KtExpression,
            context: BasicCallResolutionContext
    ): KotlinType? {
        if ((!ErrorUtils.containsErrorType(recordedType) && recordedType == updatedType) || updatedType == null) return updatedType

        val expressions = ArrayList<KtExpression>().also { expressions ->
            var expression: KtExpression? = argumentExpression
            while (expression != null) {
                expressions.add(expression)
                expression = deparenthesizeOrGetSelector(expression, context.statementFilter)
            }
            expressions.reverse()
        }

        var shouldBeMadeNullable: Boolean = false
        for (expression in expressions) {
            if (!(expression is KtParenthesizedExpression || expression is KtLabeledExpression || expression is KtAnnotatedExpression)) {
                shouldBeMadeNullable = hasNecessarySafeCall(expression, context.trace)
            }
            BindingContextUtils.updateRecordedType(updatedType, expression, context.trace, shouldBeMadeNullable)
        }

        return context.trace.getType(argumentExpression)
    }

    private fun deparenthesizeOrGetSelector(expression: KtExpression, statementFilter: StatementFilter): KtExpression? {
        val deparenthesized = KtPsiUtil.deparenthesizeOnce(expression)
        if (deparenthesized != expression) return deparenthesized

        return when (expression) {
            is KtBlockExpression -> statementFilter.getLastStatementInABlock(expression)
            is KtQualifiedExpression -> expression.selectorExpression
            else -> null
        }
    }

    private fun hasNecessarySafeCall(expression: KtExpression, trace: BindingTrace): Boolean {
        // We are interested in type of the last call:
        // 'a.b?.foo()' is safe call, but 'a?.b.foo()' is not.
        // Since receiver is 'a.b' and selector is 'foo()',
        // we can only check if an expression is safe call.
        if (expression !is KtSafeQualifiedExpression) return false

        //If a receiver type is not null, then this safe expression is useless, and we don't need to make the result type nullable.
        val expressionType = trace.getType(expression.receiverExpression)
        return expressionType != null && TypeUtils.isNullableType(expressionType)
    }

    private fun bindResolvedCall(context: BasicCallResolutionContext, trace: BindingTrace, simpleResolvedCall: NewResolvedCallImpl<*>) {
        reportCallDiagnostic(context, trace, simpleResolvedCall.completedCall)
        val tracing = simpleResolvedCall.completedCall.kotlinCall.psiKotlinCall.tracingStrategy

        tracing.bindReference(trace, simpleResolvedCall)
        tracing.bindResolvedCall(trace, simpleResolvedCall)
    }

    private fun bindResolvedCall(context: BasicCallResolutionContext, trace: BindingTrace, variableAsFunction: NewVariableAsFunctionResolvedCallImpl) {
        reportCallDiagnostic(context, trace, variableAsFunction.variableCall.completedCall)
        reportCallDiagnostic(context, trace, variableAsFunction.functionCall.completedCall)

        val outerTracingStrategy = variableAsFunction.completedCall.kotlinCall.psiKotlinCall.tracingStrategy
        outerTracingStrategy.bindReference(trace, variableAsFunction.variableCall)
        outerTracingStrategy.bindResolvedCall(trace, variableAsFunction)
        variableAsFunction.functionCall.kotlinCall.psiKotlinCall.tracingStrategy.bindReference(trace, variableAsFunction.functionCall)
    }

    private fun reportCallDiagnostic(
            context: BasicCallResolutionContext,
            trace: BindingTrace,
            completedCall: CompletedKotlinCall.Simple
    ) {
        var reported: Boolean
        val reportTrackedTrace = object : BindingTrace by trace {
            override fun report(diagnostic: Diagnostic) {
                trace.report(diagnostic)
                reported = true
            }
        }
        val diagnosticReporter = DiagnosticReporterByTrackingStrategy(constantExpressionEvaluator, context, reportTrackedTrace, completedCall.kotlinCall.psiKotlinCall)

        for (diagnostic in completedCall.resolutionStatus.diagnostics) {
            reported = false
            diagnostic.report(diagnosticReporter)
            if (!reported && REPORT_MISSING_NEW_INFERENCE_DIAGNOSTIC) {
                if (diagnostic.candidateApplicability.isSuccess) {
                    trace.report(Errors.NEW_INFERENCE_DIAGNOSTIC.on(diagnosticReporter.psiKotlinCall.psiCall.callElement, "Missing diagnostic: $diagnostic"))
                }
                else {
                    trace.report(Errors.NEW_INFERENCE_ERROR.on(diagnosticReporter.psiKotlinCall.psiCall.callElement, "Missing diagnostic: $diagnostic"))
                }
            }
        }
    }
}

sealed class NewAbstractResolvedCall<D : CallableDescriptor>(): ResolvedCall<D> {
    abstract val argumentMappingByOriginal: Map<ValueParameterDescriptor, ResolvedCallArgument>
    abstract val kotlinCall: KotlinCall

    private var argumentToParameterMap: Map<ValueArgument, ArgumentMatchImpl>? = null
    private val _valueArguments: Map<ValueParameterDescriptor, ResolvedValueArgument> by lazy { createValueArguments() }

    override fun getCall(): Call = kotlinCall.psiKotlinCall.psiCall

    override fun getValueArguments(): Map<ValueParameterDescriptor, ResolvedValueArgument> = _valueArguments

    override fun getValueArgumentsByIndex(): List<ResolvedValueArgument>? {
        val arguments = ArrayList<ResolvedValueArgument?>(candidateDescriptor.valueParameters.size)
        for (i in 0..candidateDescriptor.valueParameters.size - 1) {
            arguments.add(null)
        }

        for ((parameterDescriptor, value) in valueArguments) {
            val oldValue = arguments.set(parameterDescriptor.index, value)
            if (oldValue != null) {
                return null
            }
        }

        if (arguments.any { it == null }) return null

        @Suppress("UNCHECKED_CAST")
        return arguments as List<ResolvedValueArgument>
    }

    override fun getArgumentMapping(valueArgument: ValueArgument): ArgumentMapping {
        if (argumentToParameterMap == null) {
            argumentToParameterMap = argumentToParameterMap(resultingDescriptor, valueArguments)
        }
        return argumentToParameterMap!![valueArgument] ?: ArgumentUnmapped
    }

    override fun getDataFlowInfoForArguments() = object : DataFlowInfoForArguments {
        override fun getResultInfo() = kotlinCall.psiKotlinCall.resultDataFlowInfo
        override fun getInfo(valueArgument: ValueArgument): DataFlowInfo {
            val externalPsiCallArgument = kotlinCall.externalArgument?.psiCallArgument
            if (externalPsiCallArgument?.valueArgument == valueArgument) {
                return externalPsiCallArgument.dataFlowInfoAfterThisArgument
            }
            kotlinCall.argumentsInParenthesis.find { it.psiCallArgument.valueArgument == valueArgument }?.let {
                return it.psiCallArgument.dataFlowInfoAfterThisArgument
            }

            // valueArgument is not found
            // may be we should return initial DataFlowInfo but I think that it isn't important
            return kotlinCall.psiKotlinCall.resultDataFlowInfo
        }
    }

    private fun argumentToParameterMap(
            resultingDescriptor: CallableDescriptor,
            valueArguments: Map<ValueParameterDescriptor, ResolvedValueArgument>
    ): Map<ValueArgument, ArgumentMatchImpl> =
            LinkedHashMap<ValueArgument, ArgumentMatchImpl>().also { result ->
                for (parameter in resultingDescriptor.valueParameters) {
                    val resolvedArgument = valueArguments[parameter] ?: continue
                    for (arguments in resolvedArgument.arguments) {
                        result[arguments] = ArgumentMatchImpl(parameter).apply { recordMatchStatus(ArgumentMatchStatus.SUCCESS) }
                    }
                }
            }

    private fun createValueArguments(): Map<ValueParameterDescriptor, ResolvedValueArgument> =
            LinkedHashMap<ValueParameterDescriptor, ResolvedValueArgument>().also { result ->
                for ((originalParameter, resolvedCallArgument) in argumentMappingByOriginal) {
                    val resultingParameter = resultingDescriptor.valueParameters[originalParameter.index]
                    result[resultingParameter] = when (resolvedCallArgument) {
                        ResolvedCallArgument.DefaultArgument ->
                            DefaultValueArgument.DEFAULT
                        is ResolvedCallArgument.SimpleArgument -> {
                            val valueArgument = resolvedCallArgument.callArgument.psiCallArgument.valueArgument
                            if (resultingParameter.isVararg)
                                VarargValueArgument().apply { addArgument(valueArgument) }
                            else
                                ExpressionValueArgument(valueArgument)
                        }
                        is ResolvedCallArgument.VarargArgument ->
                            VarargValueArgument().apply {
                                resolvedCallArgument.arguments.map { it.psiCallArgument.valueArgument }.forEach { addArgument(it) }
                            }
                    }
                }
            }

}

class NewResolvedCallImpl<D : CallableDescriptor>(
        val completedCall: CompletedKotlinCall.Simple
): NewAbstractResolvedCall<D>() {
    override val kotlinCall: KotlinCall get() = completedCall.kotlinCall

    override fun getStatus(): ResolutionStatus = completedCall.resolutionStatus.resultingApplicability.toResolutionStatus()

    override val argumentMappingByOriginal: Map<ValueParameterDescriptor, ResolvedCallArgument>
        get() = completedCall.argumentMappingByOriginal

    override fun getCandidateDescriptor(): D = completedCall.candidateDescriptor as D
    override fun getResultingDescriptor(): D = completedCall.resultingDescriptor as D
    override fun getExtensionReceiver(): ReceiverValue? = completedCall.extensionReceiver?.receiverValue
    override fun getDispatchReceiver(): ReceiverValue? = completedCall.dispatchReceiver?.receiverValue
    override fun getExplicitReceiverKind(): ExplicitReceiverKind = completedCall.explicitReceiverKind

    override fun getTypeArguments(): Map<TypeParameterDescriptor, KotlinType> {
        val typeParameters = candidateDescriptor.typeParameters.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return typeParameters.zip(completedCall.typeArguments).toMap()
    }

    override fun getSmartCastDispatchReceiverType(): KotlinType? = null // todo

    fun ResolutionCandidateApplicability.toResolutionStatus(): ResolutionStatus = when (this) {
        ResolutionCandidateApplicability.RESOLVED, ResolutionCandidateApplicability.RESOLVED_LOW_PRIORITY -> ResolutionStatus.SUCCESS
        else -> ResolutionStatus.OTHER_ERROR
    }
}

class NewVariableAsFunctionResolvedCallImpl(
        val completedCall: CompletedKotlinCall.VariableAsFunction,
        override val variableCall: NewResolvedCallImpl<VariableDescriptor>,
        override val functionCall: NewResolvedCallImpl<FunctionDescriptor>
): VariableAsFunctionResolvedCall, ResolvedCall<FunctionDescriptor> by functionCall

class StubOnlyResolvedCall<D : CallableDescriptor>(val candidate: SimpleKotlinResolutionCandidate): NewAbstractResolvedCall<D>() {
    override fun getStatus() = ResolutionStatus.UNKNOWN_STATUS

    override fun getCandidateDescriptor(): D = candidate.candidateDescriptor as D
    override fun getResultingDescriptor(): D = candidateDescriptor
    override fun getExtensionReceiver() = candidate.extensionReceiver?.receiver?.receiverValue
    override fun getDispatchReceiver() = candidate.dispatchReceiverArgument?.receiver?.receiverValue
    override fun getExplicitReceiverKind() = candidate.explicitReceiverKind

    override fun getTypeArguments(): Map<TypeParameterDescriptor, KotlinType> = emptyMap()

    override fun getSmartCastDispatchReceiverType(): KotlinType? = null

    override val argumentMappingByOriginal: Map<ValueParameterDescriptor, ResolvedCallArgument>
        get() = candidate.argumentMappingByOriginal
    override val kotlinCall: KotlinCall get() = candidate.kotlinCall
}

class StubOnlyVariableAsFunctionCall(
        override val variableCall: StubOnlyResolvedCall<VariableDescriptor>,
        override val functionCall: StubOnlyResolvedCall<FunctionDescriptor>
) : VariableAsFunctionResolvedCall, ResolvedCall<FunctionDescriptor> by functionCall
