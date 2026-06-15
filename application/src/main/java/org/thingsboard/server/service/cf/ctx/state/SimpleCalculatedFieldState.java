/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.cf.ctx.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.cf.CalculatedFieldType;
import org.thingsboard.server.common.data.cf.configuration.Output;
import org.thingsboard.server.service.cf.CalculatedFieldResult;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.cf.configuration.OutputType;
import org.thingsboard.server.actors.TbActorRef;
import net.objecthunter.exp4j.Expression;
import org.thingsboard.common.util.NumberUtils;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.service.cf.TelemetryCalculatedFieldResult;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
public class SimpleCalculatedFieldState extends BaseCalculatedFieldState {

    @Override
    public CalculatedFieldType getType() {
        return CalculatedFieldType.SIMPLE;
    }

    @Override
    public ListenableFuture<CalculatedFieldResult> performCalculation(CalculatedFieldCtx ctx) {
        var expr = ctx.getCustomExpression().get();

        for (Map.Entry<String, ArgumentEntry> entry : this.arguments.entrySet()) {
            try {
                BasicKvEntry kvEntry = ((SingleValueArgumentEntry) entry.getValue()).getKvEntryValue();
                double value = switch (kvEntry.getDataType()) {
                    case LONG -> kvEntry.getLongValue().map(Long::doubleValue).orElseThrow();
                    case DOUBLE -> kvEntry.getDoubleValue().orElseThrow();
                    case BOOLEAN -> kvEntry.getBooleanValue().map(b -> b ? 1.0 : 0.0).orElseThrow();
                    case STRING, JSON -> Double.parseDouble(kvEntry.getValueAsString());
                };
                expr.setVariable(entry.getKey(), value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Argument '" + entry.getKey() + "' is not a number.");
            }
        }

        double expressionResult = expr.evaluate();

        Output output = ctx.getOutput();
        Object result = formatResult(expressionResult, output.getDecimalsByDefault());
        JsonNode outputResult = createResultJson(ctx, result);

        return Futures.immediateFuture(new CalculatedFieldResult(output.getType(), output.getScope(), outputResult));
    }

    private JsonNode createResultJson(CalculatedFieldCtx ctx, Object result) {
        Output output = ctx.getOutput();
        String outputName = output.getName();
        ObjectNode valuesNode = JacksonUtil.newObjectNode();
        if (result instanceof Double doubleValue) {
            valuesNode.put(outputName, doubleValue);
        } else if (result instanceof Integer integerValue) {
            valuesNode.put(outputName, integerValue);
        } else {
            valuesNode.set(outputName, JacksonUtil.valueToTree(result));
        }
        if (output.getType() == OutputType.ATTRIBUTES || !ctx.isUseLatestTs()) {
            return valuesNode;
        }
        long latestTs = getLatestTimestamp();
        if (latestTs == DEFAULT_LAST_UPDATE_TS) {
            return valuesNode;
        }
        ObjectNode resultNode = JacksonUtil.newObjectNode();
        resultNode.put("ts", latestTs);
        resultNode.set("values", valuesNode);
        return resultNode;
    }

    @Override
    protected void validateNewEntry(String key, ArgumentEntry newEntry) {
        if (newEntry instanceof TsRollingArgumentEntry) {
            throw new IllegalArgumentException("Unsupported argument type detected for argument: " + key + ". " +
                                               "Rolling argument entry is not supported for simple calculated fields.");
        }
    }

    @Override
    public ListenableFuture<CalculatedFieldResult> performCalculation(Map<String, ArgumentEntry> updatedArgs, CalculatedFieldCtx ctx) {
        double expressionResult = ctx.evaluateSimpleExpression(expression.get(), this);

        Output output = ctx.getOutput();
        Object result = NumberUtils.roundResult(expressionResult, output.getDecimalsByDefault());
        JsonNode outputResult = createResultJson(output.getName(), result);

        return Futures.immediateFuture(TelemetryCalculatedFieldResult.builder()
                .outputStrategy(output.getStrategy())
                .type(output.getType())
                .scope(output.getScope())
                .result(outputResult)
                .build());
    }

    private ThreadLocal<Expression> expression;

    public SimpleCalculatedFieldState(EntityId entityId) {
        super(entityId);
    }

    private JsonNode createResultJson(String outputName, Object result) {
        ObjectNode valuesNode = JacksonUtil.newObjectNode();
        if (result instanceof Double doubleValue) {
            valuesNode.put(outputName, doubleValue);
        } else if (result instanceof Integer integerValue) {
            valuesNode.put(outputName, integerValue);
        } else {
            valuesNode.set(outputName, JacksonUtil.valueToTree(result));
        }
        return toResultNode(valuesNode);
    }

    @Override
    public void setCtx(CalculatedFieldCtx ctx, TbActorRef actorCtx) {
        super.setCtx(ctx, actorCtx);
        this.expression = ctx.getSimpleExpressions().get(ctx.getExpression());
    }

}
