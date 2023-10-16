package com.facebook.presto.hive.rule;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.Domain;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.expressions.DefaultRowExpressionTraversalVisitor;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.relation.DomainTranslator;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.HashSet;
import java.util.Set;

import static com.facebook.presto.common.predicate.TupleDomain.withColumnDomains;

public final class FilterPushdownUtils
{
    private FilterPushdownUtils() {}

    public static TupleDomain<Subfield> getDomainPredicate(
            DomainTranslator.ExtractionResult<Subfield> decomposedFilter,
            TupleDomain<ColumnHandle> unenforcedConstraint)
    {
        return withColumnDomains(ImmutableMap.<Subfield, Domain>builder()
                .putAll(unenforcedConstraint
                        .transform(columnHandle -> new Subfield(columnHandle.getName(), ImmutableList.of()))
                        .getDomains()
                        .orElse(ImmutableMap.of()))
                .putAll(decomposedFilter.getTupleDomain()
                        .transform(subfield -> !isEntireColumn(subfield) ? subfield : null)
                        .getDomains()
                        .orElse(ImmutableMap.of()))
                .build());
    }

    public static Set<String> getPredicateColumnNames(
            RowExpression optimizedRemainingExpression,
            TupleDomain<Subfield> domainPredicate)
    {
        Set<String> predicateColumnNames = new HashSet<>();
        domainPredicate.getDomains().get().keySet().stream()
                .map(Subfield::getRootName)
                .forEach(predicateColumnNames::add);
        // Include only columns referenced in the optimized expression. Although the expression is sent to the worker node
        // unoptimized, the worker is expected to optimize the expression before executing.
        extractVariableExpressions(optimizedRemainingExpression).stream()
                .map(VariableReferenceExpression::getName)
                .forEach(predicateColumnNames::add);

        return predicateColumnNames;
    }

    private static boolean isEntireColumn(Subfield subfield)
    {
        return subfield.getPath().isEmpty();
    }

    private static Set<VariableReferenceExpression> extractVariableExpressions(RowExpression expression)
    {
        ImmutableSet.Builder<VariableReferenceExpression> builder = ImmutableSet.builder();
        expression.accept(new VariableReferenceBuilderVisitor(), builder);
        return builder.build();
    }

    private static class VariableReferenceBuilderVisitor
            extends DefaultRowExpressionTraversalVisitor<ImmutableSet.Builder<VariableReferenceExpression>>
    {
        @Override
        public Void visitVariableReference(
                VariableReferenceExpression variable,
                ImmutableSet.Builder<VariableReferenceExpression> builder)
        {
            builder.add(variable);
            return null;
        }
    }
}
