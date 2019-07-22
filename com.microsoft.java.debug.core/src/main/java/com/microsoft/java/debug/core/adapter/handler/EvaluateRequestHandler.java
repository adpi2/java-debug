/*******************************************************************************
* Copyright (c) 2017-2019 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.adapter.handler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.DebugSettings;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.variables.IVariableFormatter;
import com.microsoft.java.debug.core.adapter.variables.JavaLogicalStructureManager;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.adapter.variables.VariableDetailUtils;
import com.microsoft.java.debug.core.adapter.variables.VariableProxy;
import com.microsoft.java.debug.core.adapter.variables.VariableUtils;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.EvaluateArguments;
import com.microsoft.java.debug.core.protocol.Responses;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;

public class EvaluateRequestHandler implements IDebugRequestHandler {
    protected final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.EVALUATE);
    }

    @Override
    public CompletableFuture<Response> handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        EvaluateArguments evalArguments = (EvaluateArguments) arguments;
        final boolean showStaticVariables = DebugSettings.getCurrent().showStaticVariables;
        Map<String, Object> options = context.getVariableFormatter().getDefaultOptions();
        VariableUtils.applyFormatterOptions(options, evalArguments.format != null && evalArguments.format.hex);
        String expression = evalArguments.expression;

        if (StringUtils.isBlank(expression)) {
            throw new CompletionException(AdapterUtils.createUserErrorDebugException(
                "Failed to evaluate. Reason: Empty expression cannot be evaluated.",
                ErrorCode.EVALUATION_COMPILE_ERROR));
        }
        StackFrameReference stackFrameReference = (StackFrameReference) context.getRecyclableIdPool().getObjectById(evalArguments.frameId);
        if (stackFrameReference == null) {
            // stackFrameReference is null means the given thread is running
            throw new CompletionException(AdapterUtils.createUserErrorDebugException(
                    "Evaluation failed because the thread is not suspended.",
                    ErrorCode.EVALUATE_NOT_SUSPENDED_THREAD));
        }

        return CompletableFuture.supplyAsync(() -> {
            IEvaluationProvider engine = context.getProvider(IEvaluationProvider.class);
            try {
                Value value = engine.evaluate(expression, stackFrameReference.getThread(), stackFrameReference.getDepth()).get();
                IVariableFormatter variableFormatter = context.getVariableFormatter();
                if (value instanceof VoidValue) {
                    response.body = new Responses.EvaluateResponseBody(value.toString(), 0, "<void>", 0);
                    return response;
                }
                long threadId = stackFrameReference.getThread().uniqueID();
                if (value instanceof ObjectReference) {
                    VariableProxy varProxy = new VariableProxy(stackFrameReference.getThread(), "eval", value);
                    int indexedVariables = -1;
                    Value sizeValue = null;
                    if (value instanceof ArrayReference) {
                        indexedVariables = ((ArrayReference) value).length();
                    } else if (value instanceof ObjectReference && DebugSettings.getCurrent().showLogicalStructure
                            && engine != null
                            && JavaLogicalStructureManager.isIndexedVariable((ObjectReference) value)) {
                        try {
                            sizeValue = JavaLogicalStructureManager.getLogicalSize((ObjectReference) value, stackFrameReference.getThread(), engine);
                            if (sizeValue != null && sizeValue instanceof IntegerValue) {
                                indexedVariables = ((IntegerValue) sizeValue).value();
                            }
                        } catch (CancellationException | IllegalArgumentException | InterruptedException
                                | ExecutionException | UnsupportedOperationException e) {
                            logger.log(Level.INFO,
                                    String.format("Failed to get the logical size for the type %s.", value.type().name()), e);
                        }
                    }
                    int referenceId = 0;
                    if (indexedVariables > 0 || (indexedVariables < 0 && VariableUtils.hasChildren(value, showStaticVariables))) {
                        referenceId = context.getRecyclableIdPool().addObject(threadId, varProxy);
                    }

                    String valueString = variableFormatter.valueToString(value, options);
                    String detailsString = null;
                    if (sizeValue != null) {
                        detailsString = "size=" + variableFormatter.valueToString(sizeValue, options);
                    } else if (DebugSettings.getCurrent().showToString) {
                        detailsString = VariableDetailUtils.formatDetailsValue(value, stackFrameReference.getThread(), variableFormatter, options, engine);
                    }

                    response.body = new Responses.EvaluateResponseBody((detailsString == null) ? valueString : valueString + " " + detailsString,
                            referenceId, variableFormatter.typeToString(value == null ? null : value.type(), options),
                            Math.max(indexedVariables, 0));
                    return response;
                }
                // for primitive value
                response.body = new Responses.EvaluateResponseBody(variableFormatter.valueToString(value, options), 0,
                        variableFormatter.typeToString(value == null ? null : value.type(), options), 0);
                return response;
            } catch (InterruptedException | ExecutionException e) {
                Throwable cause = e;
                if (e instanceof ExecutionException && e.getCause() != null) {
                    cause = e.getCause();
                }

                if (cause instanceof DebugException) {
                    throw new CompletionException(cause);
                }
                throw AdapterUtils.createCompletionException(
                    String.format("Cannot evaluate because of %s.", cause.toString()),
                    ErrorCode.EVALUATE_FAILURE,
                    cause);
            }
        });
    }
}
