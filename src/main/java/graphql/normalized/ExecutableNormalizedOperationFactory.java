package graphql.normalized;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.LinkedHashMultimap;
import graphql.Assert;
import graphql.ExperimentalApi;
import graphql.GraphQLContext;
import graphql.PublicApi;
import graphql.collect.ImmutableKit;
import graphql.execution.AbortExecutionException;
import graphql.execution.CoercedVariables;
import graphql.execution.MergedField;
import graphql.execution.RawVariables;
import graphql.execution.ValuesResolver;
import graphql.execution.conditional.ConditionalNodes;
import graphql.execution.directives.QueryDirectives;
import graphql.execution.directives.QueryDirectivesImpl;
import graphql.introspection.Introspection;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.NodeUtil;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.normalized.incremental.DeferDeclaration;
import graphql.normalized.incremental.IncrementalNodes;
import graphql.normalized.incremental.NormalizedDeferExecutionFactory;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphQLUnmodifiedType;
import graphql.schema.impl.SchemaUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertShouldNeverHappen;
import static graphql.collect.ImmutableKit.map;
import static graphql.schema.GraphQLTypeUtil.unwrapAll;
import static graphql.util.FpKit.filterSet;
import static graphql.util.FpKit.groupingBy;
import static graphql.util.FpKit.intersection;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;

/**
 * This factory can create a {@link ExecutableNormalizedOperation} which represents what would be executed
 * during a given graphql operation.
 */
@PublicApi
public class ExecutableNormalizedOperationFactory {
    public static class Options {
        private final GraphQLContext graphQLContext;
        private final Locale locale;
        private final int maxChildrenDepth;

        private final boolean deferSupport;

        private Options(GraphQLContext graphQLContext,
                        Locale locale,
                        int maxChildrenDepth,
                        boolean deferSupport) {
            this.graphQLContext = graphQLContext;
            this.locale = locale;
            this.maxChildrenDepth = maxChildrenDepth;
            this.deferSupport = deferSupport;
        }

        public static Options defaultOptions() {
            return new Options(
                    GraphQLContext.getDefault(),
                    Locale.getDefault(),
                    Integer.MAX_VALUE,
                    false);
        }

        /**
         * Locale to use when parsing the query.
         * <p>
         * e.g. can be passed to {@link graphql.schema.Coercing} for parsing.
         *
         * @param locale the locale to use
         *
         * @return new options object to use
         */
        public Options locale(Locale locale) {
            return new Options(this.graphQLContext, locale, this.maxChildrenDepth, true);
        }

        /**
         * Context object to use when parsing the operation.
         * <p>
         * Can be used to intercept input values e.g. using {@link graphql.execution.values.InputInterceptor}.
         *
         * @param graphQLContext the context to use
         *
         * @return new options object to use
         */
        public Options graphQLContext(GraphQLContext graphQLContext) {
            return new Options(graphQLContext, this.locale, this.maxChildrenDepth, true);
        }

        /**
         * Controls the maximum depth of the operation. Can be used to prevent
         * against malicious operations.
         *
         * @param maxChildrenDepth the max depth
         *
         * @return new options object to use
         */
        public Options maxChildrenDepth(int maxChildrenDepth) {
            return new Options(this.graphQLContext, this.locale, maxChildrenDepth, true);
        }

        /**
         * Controls whether defer execution is supported when creating instances of {@link ExecutableNormalizedOperation}.
         *
         * @param deferSupport true to enable support for defer
         *
         * @return new options object to use
         */
        @ExperimentalApi
        public Options deferSupport(boolean deferSupport) {
            return new Options(this.graphQLContext, this.locale, this.maxChildrenDepth, deferSupport);
        }

        /**
         * @return context to use during operation parsing
         *
         * @see #graphQLContext(GraphQLContext)
         */
        public GraphQLContext getGraphQLContext() {
            return graphQLContext;
        }

        /**
         * @return locale to use during operation parsing
         *
         * @see #locale(Locale)
         */
        public Locale getLocale() {
            return locale;
        }

        /**
         * @return maximum children depth before aborting parsing
         *
         * @see #maxChildrenDepth(int)
         */
        public int getMaxChildrenDepth() {
            return maxChildrenDepth;
        }

        /**
         * @return whether support for defer is enabled
         *
         * @see #deferSupport(boolean)
         */
        @ExperimentalApi
        public boolean getDeferSupport() {
            return deferSupport;
        }
    }

    private final ConditionalNodes conditionalNodes = new ConditionalNodes();
    private final IncrementalNodes incrementalNodes = new IncrementalNodes();

    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema         the schema to be used
     * @param document              the {@link Document} holding the operation text
     * @param operationName         the operation name to use
     * @param coercedVariableValues the coerced variables to use
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperation(
            GraphQLSchema graphQLSchema,
            Document document,
            String operationName,
            CoercedVariables coercedVariableValues
    ) {
        return createExecutableNormalizedOperation(
                graphQLSchema,
                document,
                operationName,
                coercedVariableValues,
                Options.defaultOptions());
    }

    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema         the schema to be used
     * @param document              the {@link Document} holding the operation text
     * @param operationName         the operation name to use
     * @param coercedVariableValues the coerced variables to use
     * @param options               the {@link Options} to use for parsing
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperation(
            GraphQLSchema graphQLSchema,
            Document document,
            String operationName,
            CoercedVariables coercedVariableValues,
            Options options
    ) {
        NodeUtil.GetOperationResult getOperationResult = NodeUtil.getOperation(document, operationName);
        return new ExecutableNormalizedOperationFactory().createNormalizedQueryImpl(graphQLSchema,
                getOperationResult.operationDefinition,
                getOperationResult.fragmentsByName,
                coercedVariableValues,
                null,
                options);
    }

    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema         the schema to be used
     * @param operationDefinition   the operation to be executed
     * @param fragments             a set of fragments associated with the operation
     * @param coercedVariableValues the coerced variables to use
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperation(GraphQLSchema graphQLSchema,
                                                                                    OperationDefinition operationDefinition,
                                                                                    Map<String, FragmentDefinition> fragments,
                                                                                    CoercedVariables coercedVariableValues) {
        return new ExecutableNormalizedOperationFactory().createNormalizedQueryImpl(graphQLSchema,
                operationDefinition,
                fragments,
                coercedVariableValues,
                null,
                Options.defaultOptions());
    }

    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema the schema to be used
     * @param document      the {@link Document} holding the operation text
     * @param operationName the operation name to use
     * @param rawVariables  the raw variables to be coerced
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperationWithRawVariables(GraphQLSchema graphQLSchema,
                                                                                                    Document document,
                                                                                                    String operationName,
                                                                                                    RawVariables rawVariables) {
        return createExecutableNormalizedOperationWithRawVariables(graphQLSchema,
                document,
                operationName,
                rawVariables,
                Options.defaultOptions());
    }


    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema  the schema to be used
     * @param document       the {@link Document} holding the operation text
     * @param operationName  the operation name to use
     * @param rawVariables   the raw variables that have not yet been coerced
     * @param locale         the {@link Locale} to use during coercion
     * @param graphQLContext the {@link GraphQLContext} to use during coercion
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperationWithRawVariables(
            GraphQLSchema graphQLSchema,
            Document document,
            String operationName,
            RawVariables rawVariables,
            GraphQLContext graphQLContext,
            Locale locale
    ) {
        return createExecutableNormalizedOperationWithRawVariables(
                graphQLSchema,
                document,
                operationName,
                rawVariables,
                Options.defaultOptions().graphQLContext(graphQLContext).locale(locale));
    }


    /**
     * This will create a runtime representation of the graphql operation that would be executed
     * in a runtime sense.
     *
     * @param graphQLSchema the schema to be used
     * @param document      the {@link Document} holding the operation text
     * @param operationName the operation name to use
     * @param rawVariables  the raw variables that have not yet been coerced
     * @param options       the {@link Options} to use for parsing
     *
     * @return a runtime representation of the graphql operation.
     */
    public static ExecutableNormalizedOperation createExecutableNormalizedOperationWithRawVariables(GraphQLSchema graphQLSchema,
                                                                                                    Document document,
                                                                                                    String operationName,
                                                                                                    RawVariables rawVariables,
                                                                                                    Options options) {
        NodeUtil.GetOperationResult getOperationResult = NodeUtil.getOperation(document, operationName);

        return new ExecutableNormalizedOperationFactory().createExecutableNormalizedOperationImplWithRawVariables(graphQLSchema,
                getOperationResult.operationDefinition,
                getOperationResult.fragmentsByName,
                rawVariables,
                options
        );
    }

    private ExecutableNormalizedOperation createExecutableNormalizedOperationImplWithRawVariables(GraphQLSchema graphQLSchema,
                                                                                                  OperationDefinition operationDefinition,
                                                                                                  Map<String, FragmentDefinition> fragments,
                                                                                                  RawVariables rawVariables,
                                                                                                  Options options) {
        List<VariableDefinition> variableDefinitions = operationDefinition.getVariableDefinitions();
        CoercedVariables coercedVariableValues = ValuesResolver.coerceVariableValues(graphQLSchema,
                variableDefinitions,
                rawVariables,
                options.getGraphQLContext(),
                options.getLocale());
        Map<String, NormalizedInputValue> normalizedVariableValues = ValuesResolver.getNormalizedVariableValues(graphQLSchema,
                variableDefinitions,
                rawVariables,
                options.getGraphQLContext(),
                options.getLocale());
        return createNormalizedQueryImpl(graphQLSchema,
                operationDefinition,
                fragments,
                coercedVariableValues,
                normalizedVariableValues,
                options);
    }

    /**
     * Creates a new ExecutableNormalizedOperation for the provided query
     */
    private ExecutableNormalizedOperation createNormalizedQueryImpl(GraphQLSchema graphQLSchema,
                                                                    OperationDefinition operationDefinition,
                                                                    Map<String, FragmentDefinition> fragments,
                                                                    CoercedVariables coercedVariableValues,
                                                                    @Nullable Map<String, NormalizedInputValue> normalizedVariableValues,
                                                                    Options options) {
        FieldCollectorNormalizedQueryParams parameters = FieldCollectorNormalizedQueryParams
                .newParameters()
                .fragments(fragments)
                .schema(graphQLSchema)
                .coercedVariables(coercedVariableValues.toMap())
                .normalizedVariables(normalizedVariableValues)
                .build();

        GraphQLObjectType rootType = SchemaUtil.getOperationRootType(graphQLSchema, operationDefinition);

        CollectNFResult collectFromOperationResult = collectFromOperation(parameters, operationDefinition, rootType, options.getDeferSupport());

        ImmutableListMultimap.Builder<Field, ExecutableNormalizedField> fieldToNormalizedField = ImmutableListMultimap.builder();
        ImmutableMap.Builder<ExecutableNormalizedField, MergedField> normalizedFieldToMergedField = ImmutableMap.builder();
        ImmutableMap.Builder<ExecutableNormalizedField, QueryDirectives> normalizedFieldToQueryDirectives = ImmutableMap.builder();
        ImmutableListMultimap.Builder<FieldCoordinates, ExecutableNormalizedField> coordinatesToNormalizedFields = ImmutableListMultimap.builder();

        BiConsumer<ExecutableNormalizedField, MergedField> captureMergedField = (enf, mergedFld) -> {
            // QueryDirectivesImpl is a lazy object and only computes itself when asked for
            QueryDirectives queryDirectives = new QueryDirectivesImpl(mergedFld, graphQLSchema, coercedVariableValues.toMap(), options.getGraphQLContext(), options.getLocale());
            normalizedFieldToQueryDirectives.put(enf, queryDirectives);
            normalizedFieldToMergedField.put(enf, mergedFld);
        };

        LinkedHashMultimap<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution = LinkedHashMultimap.create();
        normalizedFieldToDeferExecution.putAll(collectFromOperationResult.normalizedFieldToDeferExecution);

        Consumer<CollectNFResult> captureCollectNFResult = (collectNFResult ->
                normalizedFieldToDeferExecution.putAll(collectNFResult.normalizedFieldToDeferExecution)
        );

        for (ExecutableNormalizedField topLevel : collectFromOperationResult.children) {
            ImmutableList<FieldAndAstParent> fieldAndAstParents = collectFromOperationResult.normalizedFieldToAstFields.get(topLevel);
            MergedField mergedField = newMergedField(fieldAndAstParents);

            captureMergedField.accept(topLevel, mergedField);

            updateFieldToNFMap(topLevel, fieldAndAstParents, fieldToNormalizedField);
            updateCoordinatedToNFMap(coordinatesToNormalizedFields, topLevel);

            buildFieldWithChildren(
                    topLevel,
                    fieldAndAstParents,
                    parameters,
                    fieldToNormalizedField,
                    captureMergedField,
                    coordinatesToNormalizedFields,
                    1,
                    options.getMaxChildrenDepth(),
                    captureCollectNFResult,
                    options.getDeferSupport());
        }
        for (FieldCollectorNormalizedQueryParams.PossibleMerger possibleMerger : parameters.getPossibleMergerList()) {
            List<ExecutableNormalizedField> childrenWithSameResultKey = possibleMerger.parent.getChildrenWithSameResultKey(possibleMerger.resultKey);
            ENFMerger.merge(possibleMerger.parent, childrenWithSameResultKey, graphQLSchema, normalizedFieldToDeferExecution, options.deferSupport);
        }

        if (options.deferSupport) {
            NormalizedDeferExecutionFactory.normalizeDeferExecutions(graphQLSchema, normalizedFieldToDeferExecution);
        }

        return new ExecutableNormalizedOperation(
                operationDefinition.getOperation(),
                operationDefinition.getName(),
                new ArrayList<>(collectFromOperationResult.children),
                fieldToNormalizedField.build(),
                normalizedFieldToMergedField.build(),
                normalizedFieldToQueryDirectives.build(),
                coordinatesToNormalizedFields.build()
        );
    }


    private void buildFieldWithChildren(ExecutableNormalizedField executableNormalizedField,
                                        ImmutableList<FieldAndAstParent> fieldAndAstParents,
                                        FieldCollectorNormalizedQueryParams fieldCollectorNormalizedQueryParams,
                                        ImmutableListMultimap.Builder<Field, ExecutableNormalizedField> fieldNormalizedField,
                                        BiConsumer<ExecutableNormalizedField, MergedField> captureMergedField,
                                        ImmutableListMultimap.Builder<FieldCoordinates, ExecutableNormalizedField> coordinatesToNormalizedFields,
                                        int curLevel,
                                        int maxLevel,
                                        Consumer<CollectNFResult> captureCollectNFResult,
                                        boolean deferSupport) {
        if (curLevel > maxLevel) {
            throw new AbortExecutionException("Maximum query depth exceeded " + curLevel + " > " + maxLevel);
        }

        CollectNFResult nextLevel = collectFromMergedField(fieldCollectorNormalizedQueryParams, executableNormalizedField, fieldAndAstParents, curLevel + 1, deferSupport);

        captureCollectNFResult.accept(nextLevel);

        for (ExecutableNormalizedField childENF : nextLevel.children) {
            executableNormalizedField.addChild(childENF);
            ImmutableList<FieldAndAstParent> childFieldAndAstParents = nextLevel.normalizedFieldToAstFields.get(childENF);

            MergedField mergedField = newMergedField(childFieldAndAstParents);
            captureMergedField.accept(childENF, mergedField);

            updateFieldToNFMap(childENF, childFieldAndAstParents, fieldNormalizedField);
            updateCoordinatedToNFMap(coordinatesToNormalizedFields, childENF);

            buildFieldWithChildren(childENF,
                    childFieldAndAstParents,
                    fieldCollectorNormalizedQueryParams,
                    fieldNormalizedField,
                    captureMergedField,
                    coordinatesToNormalizedFields,
                    curLevel + 1,
                    maxLevel,
                    captureCollectNFResult,
                    deferSupport);
        }
    }

    private static MergedField newMergedField(ImmutableList<FieldAndAstParent> fieldAndAstParents) {
        return MergedField.newMergedField(map(fieldAndAstParents, fieldAndAstParent -> fieldAndAstParent.field)).build();
    }

    private void updateFieldToNFMap(ExecutableNormalizedField executableNormalizedField,
                                    ImmutableList<FieldAndAstParent> mergedField,
                                    ImmutableListMultimap.Builder<Field, ExecutableNormalizedField> fieldToNormalizedField) {
        for (FieldAndAstParent astField : mergedField) {
            fieldToNormalizedField.put(astField.field, executableNormalizedField);
        }
    }

    private void updateCoordinatedToNFMap(ImmutableListMultimap.Builder<FieldCoordinates, ExecutableNormalizedField> coordinatesToNormalizedFields, ExecutableNormalizedField topLevel) {
        for (String objectType : topLevel.getObjectTypeNames()) {
            FieldCoordinates coordinates = FieldCoordinates.coordinates(objectType, topLevel.getFieldName());
            coordinatesToNormalizedFields.put(coordinates, topLevel);
        }
    }

    private static class FieldAndAstParent {
        final Field field;
        final GraphQLCompositeType astParentType;

        private FieldAndAstParent(Field field, GraphQLCompositeType astParentType) {
            this.field = field;
            this.astParentType = astParentType;
        }
    }


    public static class CollectNFResult {
        private final Collection<ExecutableNormalizedField> children;
        private final ImmutableListMultimap<ExecutableNormalizedField, FieldAndAstParent> normalizedFieldToAstFields;
        private final ImmutableSetMultimap<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution;

        public CollectNFResult(
                Collection<ExecutableNormalizedField> children,
                ImmutableListMultimap<ExecutableNormalizedField, FieldAndAstParent> normalizedFieldToAstFields,
                ImmutableSetMultimap<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution
        ) {
            this.children = children;
            this.normalizedFieldToAstFields = normalizedFieldToAstFields;
            this.normalizedFieldToDeferExecution = normalizedFieldToDeferExecution;
        }
    }


    public CollectNFResult collectFromMergedField(FieldCollectorNormalizedQueryParams parameters,
                                                  ExecutableNormalizedField executableNormalizedField,
                                                  ImmutableList<FieldAndAstParent> mergedField,
                                                  int level,
                                                  boolean deferSupport) {
        List<GraphQLFieldDefinition> fieldDefs = executableNormalizedField.getFieldDefinitions(parameters.getGraphQLSchema());
        Set<GraphQLObjectType> possibleObjects = resolvePossibleObjects(fieldDefs, parameters.getGraphQLSchema());
        if (possibleObjects.isEmpty()) {
            return new CollectNFResult(ImmutableKit.emptyList(), ImmutableListMultimap.of(), ImmutableSetMultimap.of());
        }

        List<CollectedField> collectedFields = new ArrayList<>();
        for (FieldAndAstParent fieldAndAstParent : mergedField) {
            if (fieldAndAstParent.field.getSelectionSet() == null) {
                continue;
            }
            GraphQLFieldDefinition fieldDefinition = Introspection.getFieldDef(parameters.getGraphQLSchema(), fieldAndAstParent.astParentType, fieldAndAstParent.field.getName());
            GraphQLUnmodifiedType astParentType = unwrapAll(fieldDefinition.getType());
            this.collectFromSelectionSet(parameters,
                    fieldAndAstParent.field.getSelectionSet(),
                    collectedFields,
                    (GraphQLCompositeType) astParentType,
                    possibleObjects,
                    null
            );
        }
        Map<String, List<CollectedField>> fieldsByName = fieldsByResultKey(collectedFields);
        ImmutableList.Builder<ExecutableNormalizedField> resultNFs = ImmutableList.builder();
        ImmutableListMultimap.Builder<ExecutableNormalizedField, FieldAndAstParent> normalizedFieldToAstFields = ImmutableListMultimap.builder();
        ImmutableSetMultimap.Builder<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution = ImmutableSetMultimap.builder();

        createNFs(resultNFs, parameters, fieldsByName, normalizedFieldToAstFields, level, executableNormalizedField, normalizedFieldToDeferExecution, deferSupport);

        return new CollectNFResult(resultNFs.build(), normalizedFieldToAstFields.build(), normalizedFieldToDeferExecution.build());
    }

    private Map<String, List<CollectedField>> fieldsByResultKey(List<CollectedField> collectedFields) {
        Map<String, List<CollectedField>> fieldsByName = new LinkedHashMap<>();
        for (CollectedField collectedField : collectedFields) {
            fieldsByName.computeIfAbsent(collectedField.field.getResultKey(), ignored -> new ArrayList<>()).add(collectedField);
        }
        return fieldsByName;
    }

    public CollectNFResult collectFromOperation(FieldCollectorNormalizedQueryParams parameters,
                                                OperationDefinition operationDefinition,
                                                GraphQLObjectType rootType,
                                                boolean deferSupport) {
        Set<GraphQLObjectType> possibleObjects = ImmutableSet.of(rootType);
        List<CollectedField> collectedFields = new ArrayList<>();
        collectFromSelectionSet(parameters, operationDefinition.getSelectionSet(), collectedFields, rootType, possibleObjects, null);
        // group by result key
        Map<String, List<CollectedField>> fieldsByName = fieldsByResultKey(collectedFields);
        ImmutableList.Builder<ExecutableNormalizedField> resultNFs = ImmutableList.builder();
        ImmutableListMultimap.Builder<ExecutableNormalizedField, FieldAndAstParent> normalizedFieldToAstFields = ImmutableListMultimap.builder();
        ImmutableSetMultimap.Builder<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution = ImmutableSetMultimap.builder();

        createNFs(resultNFs, parameters, fieldsByName, normalizedFieldToAstFields, 1, null, normalizedFieldToDeferExecution, deferSupport);

        return new CollectNFResult(resultNFs.build(), normalizedFieldToAstFields.build(), normalizedFieldToDeferExecution.build());
    }

    public CollectNFResult collectFromOperation(FieldCollectorNormalizedQueryParams parameters,
                                                OperationDefinition operationDefinition,
                                                GraphQLObjectType rootType) {
        return this.collectFromOperation(parameters, operationDefinition, rootType, false);
    }

    private void createNFs(ImmutableList.Builder<ExecutableNormalizedField> nfListBuilder,
                           FieldCollectorNormalizedQueryParams parameters,
                           Map<String, List<CollectedField>> fieldsByName,
                           ImmutableListMultimap.Builder<ExecutableNormalizedField, FieldAndAstParent> normalizedFieldToAstFields,
                           int level,
                           ExecutableNormalizedField parent,
                           ImmutableSetMultimap.Builder<ExecutableNormalizedField, DeferDeclaration> normalizedFieldToDeferExecution,
                           boolean deferSupport) {
        for (String resultKey : fieldsByName.keySet()) {
            List<CollectedField> fieldsWithSameResultKey = fieldsByName.get(resultKey);
            List<CollectedFieldGroup> commonParentsGroups = groupByCommonParents(fieldsWithSameResultKey, deferSupport);
            for (CollectedFieldGroup fieldGroup : commonParentsGroups) {
                ExecutableNormalizedField nf = createNF(parameters, fieldGroup, level, parent);
                if (nf == null) {
                    continue;
                }
                for (CollectedField collectedField : fieldGroup.fields) {
                    normalizedFieldToAstFields.put(nf, new FieldAndAstParent(collectedField.field, collectedField.astTypeCondition));
                }
                nfListBuilder.add(nf);
                if (deferSupport) {
                    normalizedFieldToDeferExecution.putAll(nf, fieldGroup.deferExecutions);
                }
            }
            if (commonParentsGroups.size() > 1) {
                parameters.addPossibleMergers(parent, resultKey);
            }
        }
    }

    private ExecutableNormalizedField createNF(FieldCollectorNormalizedQueryParams parameters,
                                               CollectedFieldGroup collectedFieldGroup,
                                               int level,
                                               ExecutableNormalizedField parent) {
        Field field;
        Set<GraphQLObjectType> objectTypes = collectedFieldGroup.objectTypes;
        field = collectedFieldGroup.fields.iterator().next().field;
        String fieldName = field.getName();
        GraphQLFieldDefinition fieldDefinition = Introspection.getFieldDef(parameters.getGraphQLSchema(), objectTypes.iterator().next(), fieldName);

        Map<String, Object> argumentValues = ValuesResolver.getArgumentValues(fieldDefinition.getArguments(), field.getArguments(), CoercedVariables.of(parameters.getCoercedVariableValues()), parameters.getGraphQLContext(), parameters.getLocale());
        Map<String, NormalizedInputValue> normalizedArgumentValues = null;
        if (parameters.getNormalizedVariableValues() != null) {
            normalizedArgumentValues = ValuesResolver.getNormalizedArgumentValues(fieldDefinition.getArguments(), field.getArguments(), parameters.getNormalizedVariableValues());
        }
        ImmutableList<String> objectTypeNames = map(objectTypes, GraphQLObjectType::getName);

        return ExecutableNormalizedField.newNormalizedField()
                .alias(field.getAlias())
                .resolvedArguments(argumentValues)
                .normalizedArguments(normalizedArgumentValues)
                .astArguments(field.getArguments())
                .objectTypeNames(objectTypeNames)
                .fieldName(fieldName)
                .level(level)
                .parent(parent)
                .build();
    }

    private static class CollectedFieldGroup {
        Set<GraphQLObjectType> objectTypes;
        Set<CollectedField> fields;
        Set<DeferDeclaration> deferExecutions;

        public CollectedFieldGroup(Set<CollectedField> fields, Set<GraphQLObjectType> objectTypes, Set<DeferDeclaration> deferExecutions) {
            this.fields = fields;
            this.objectTypes = objectTypes;
            this.deferExecutions = deferExecutions;
        }
    }

    private List<CollectedFieldGroup> groupByCommonParents(Collection<CollectedField> fields, boolean deferSupport) {
        if (deferSupport) {
            return groupByCommonParentsWithDeferSupport(fields);
        } else {
            return groupByCommonParentsNoDeferSupport(fields);
        }
    }

    private List<CollectedFieldGroup> groupByCommonParentsNoDeferSupport(Collection<CollectedField> fields) {
        ImmutableSet.Builder<GraphQLObjectType> objectTypes = ImmutableSet.builder();
        for (CollectedField collectedField : fields) {
            objectTypes.addAll(collectedField.objectTypes);
        }
        Set<GraphQLObjectType> allRelevantObjects = objectTypes.build();
        Map<GraphQLType, ImmutableList<CollectedField>> groupByAstParent = groupingBy(fields, fieldAndType -> fieldAndType.astTypeCondition);
        if (groupByAstParent.size() == 1) {
            return singletonList(new CollectedFieldGroup(ImmutableSet.copyOf(fields), allRelevantObjects, null));
        }
        ImmutableList.Builder<CollectedFieldGroup> result = ImmutableList.builder();
        for (GraphQLObjectType objectType : allRelevantObjects) {
            Set<CollectedField> relevantFields = filterSet(fields, field -> field.objectTypes.contains(objectType));
            result.add(new CollectedFieldGroup(relevantFields, singleton(objectType), null));
        }
        return result.build();
    }

    private List<CollectedFieldGroup> groupByCommonParentsWithDeferSupport(Collection<CollectedField> fields) {
        ImmutableSet.Builder<GraphQLObjectType> objectTypes = ImmutableSet.builder();
        ImmutableSet.Builder<DeferDeclaration> deferExecutionsBuilder = ImmutableSet.builder();

        for (CollectedField collectedField : fields) {
            objectTypes.addAll(collectedField.objectTypes);

            DeferDeclaration collectedDeferExecution = collectedField.deferExecution;

            if (collectedDeferExecution != null) {
                deferExecutionsBuilder.add(collectedDeferExecution);
            }
        }

        Set<GraphQLObjectType> allRelevantObjects = objectTypes.build();
        Set<DeferDeclaration> deferExecutions = deferExecutionsBuilder.build();

        Set<String> duplicatedLabels = listDuplicatedLabels(deferExecutions);

        if (!duplicatedLabels.isEmpty()) {
            // Query validation should pick this up
            Assert.assertShouldNeverHappen("Duplicated @defer labels are not allowed: [%s]", String.join(",", duplicatedLabels));
        }

        Map<GraphQLType, ImmutableList<CollectedField>> groupByAstParent = groupingBy(fields, fieldAndType -> fieldAndType.astTypeCondition);
        if (groupByAstParent.size() == 1) {
            return singletonList(new CollectedFieldGroup(ImmutableSet.copyOf(fields), allRelevantObjects, deferExecutions));
        }

        ImmutableList.Builder<CollectedFieldGroup> result = ImmutableList.builder();
        for (GraphQLObjectType objectType : allRelevantObjects) {
            Set<CollectedField> relevantFields = filterSet(fields, field -> field.objectTypes.contains(objectType));

            Set<DeferDeclaration> filteredDeferExecutions = deferExecutions.stream()
                    .filter(filter(objectType))
                    .collect(toCollection(LinkedHashSet::new));

            result.add(new CollectedFieldGroup(relevantFields, singleton(objectType), filteredDeferExecutions));
        }
        return result.build();
    }

    private static Predicate<DeferDeclaration> filter(GraphQLObjectType objectType) {
        return deferExecution -> {
            if (deferExecution.getTargetType() == null) {
                return true;
            }

            if (deferExecution.getTargetType().equals(objectType.getName())) {
                return true;
            }

            return objectType.getInterfaces().stream()
                    .anyMatch(inter -> inter.getName().equals(deferExecution.getTargetType()));
        };
    }

    private Set<String> listDuplicatedLabels(Collection<DeferDeclaration> deferExecutions) {
        return deferExecutions.stream()
                .map(DeferDeclaration::getLabel)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(toSet());
    }

    private void collectFromSelectionSet(FieldCollectorNormalizedQueryParams parameters,
                                         SelectionSet selectionSet,
                                         List<CollectedField> result,
                                         GraphQLCompositeType astTypeCondition,
                                         Set<GraphQLObjectType> possibleObjects,
                                         DeferDeclaration deferExecution
    ) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field) {
                collectField(parameters, result, (Field) selection, possibleObjects, astTypeCondition, deferExecution);
            } else if (selection instanceof InlineFragment) {
                collectInlineFragment(parameters, result, (InlineFragment) selection, possibleObjects, astTypeCondition);
            } else if (selection instanceof FragmentSpread) {
                collectFragmentSpread(parameters, result, (FragmentSpread) selection, possibleObjects);
            }
        }
    }

    private static class CollectedField {
        Field field;
        Set<GraphQLObjectType> objectTypes;
        GraphQLCompositeType astTypeCondition;
        DeferDeclaration deferExecution;

        public CollectedField(Field field, Set<GraphQLObjectType> objectTypes, GraphQLCompositeType astTypeCondition, DeferDeclaration deferExecution) {
            this.field = field;
            this.objectTypes = objectTypes;
            this.astTypeCondition = astTypeCondition;
            this.deferExecution = deferExecution;
        }

        public boolean isAbstract() {
            return GraphQLTypeUtil.isInterfaceOrUnion(astTypeCondition);
        }

        public boolean isConcrete() {
            return GraphQLTypeUtil.isObjectType(astTypeCondition);
        }
    }

    private void collectFragmentSpread(FieldCollectorNormalizedQueryParams parameters,
                                       List<CollectedField> result,
                                       FragmentSpread fragmentSpread,
                                       Set<GraphQLObjectType> possibleObjects
    ) {
        if (!conditionalNodes.shouldInclude(fragmentSpread,
                parameters.getCoercedVariableValues(),
                parameters.getGraphQLSchema(),
                parameters.getGraphQLContext())) {
            return;
        }
        FragmentDefinition fragmentDefinition = assertNotNull(parameters.getFragmentsByName().get(fragmentSpread.getName()));

        if (!conditionalNodes.shouldInclude(fragmentDefinition,
                parameters.getCoercedVariableValues(),
                parameters.getGraphQLSchema(),
                parameters.getGraphQLContext())) {
            return;
        }

        DeferDeclaration newDeferExecution = incrementalNodes.getDeferExecution(
                parameters.getCoercedVariableValues(),
                fragmentSpread.getDirectives(),
                fragmentDefinition.getTypeCondition()
        );

        GraphQLCompositeType newAstTypeCondition = (GraphQLCompositeType) assertNotNull(parameters.getGraphQLSchema().getType(fragmentDefinition.getTypeCondition().getName()));
        Set<GraphQLObjectType> newPossibleObjects = narrowDownPossibleObjects(possibleObjects, newAstTypeCondition, parameters.getGraphQLSchema());
        collectFromSelectionSet(parameters, fragmentDefinition.getSelectionSet(), result, newAstTypeCondition, newPossibleObjects, newDeferExecution);
    }


    private void collectInlineFragment(FieldCollectorNormalizedQueryParams parameters,
                                       List<CollectedField> result,
                                       InlineFragment inlineFragment,
                                       Set<GraphQLObjectType> possibleObjects,
                                       GraphQLCompositeType astTypeCondition
    ) {
        if (!conditionalNodes.shouldInclude(inlineFragment, parameters.getCoercedVariableValues(), parameters.getGraphQLSchema(), parameters.getGraphQLContext())) {
            return;
        }
        Set<GraphQLObjectType> newPossibleObjects = possibleObjects;
        GraphQLCompositeType newAstTypeCondition = astTypeCondition;

        if (inlineFragment.getTypeCondition() != null) {
            newAstTypeCondition = (GraphQLCompositeType) parameters.getGraphQLSchema().getType(inlineFragment.getTypeCondition().getName());
            newPossibleObjects = narrowDownPossibleObjects(possibleObjects, newAstTypeCondition, parameters.getGraphQLSchema());
        }

        DeferDeclaration newDeferExecution = incrementalNodes.getDeferExecution(
                parameters.getCoercedVariableValues(),
                inlineFragment.getDirectives(),
                inlineFragment.getTypeCondition()
        );

        collectFromSelectionSet(parameters, inlineFragment.getSelectionSet(), result, newAstTypeCondition, newPossibleObjects, newDeferExecution);
    }

    private void collectField(FieldCollectorNormalizedQueryParams parameters,
                              List<CollectedField> result,
                              Field field,
                              Set<GraphQLObjectType> possibleObjectTypes,
                              GraphQLCompositeType astTypeCondition,
                              DeferDeclaration deferExecution
    ) {
        if (!conditionalNodes.shouldInclude(field,
                parameters.getCoercedVariableValues(),
                parameters.getGraphQLSchema(),
                parameters.getGraphQLContext())) {
            return;
        }
        // this means there is actually no possible type for this field, and we are done
        if (possibleObjectTypes.isEmpty()) {
            return;
        }
        result.add(new CollectedField(field, possibleObjectTypes, astTypeCondition, deferExecution));
    }

    private Set<GraphQLObjectType> narrowDownPossibleObjects(Set<GraphQLObjectType> currentOnes,
                                                             GraphQLCompositeType typeCondition,
                                                             GraphQLSchema graphQLSchema) {

        ImmutableSet<GraphQLObjectType> resolvedTypeCondition = resolvePossibleObjects(typeCondition, graphQLSchema);
        if (currentOnes.isEmpty()) {
            return resolvedTypeCondition;
        }

        // Faster intersection, as either set often has a size of 1.
        return intersection(currentOnes, resolvedTypeCondition);
    }

    private ImmutableSet<GraphQLObjectType> resolvePossibleObjects(List<GraphQLFieldDefinition> defs, GraphQLSchema graphQLSchema) {
        ImmutableSet.Builder<GraphQLObjectType> builder = ImmutableSet.builder();

        for (GraphQLFieldDefinition def : defs) {
            GraphQLUnmodifiedType outputType = unwrapAll(def.getType());
            if (outputType instanceof GraphQLCompositeType) {
                builder.addAll(resolvePossibleObjects((GraphQLCompositeType) outputType, graphQLSchema));
            }
        }

        return builder.build();
    }

    private ImmutableSet<GraphQLObjectType> resolvePossibleObjects(GraphQLCompositeType type, GraphQLSchema graphQLSchema) {
        if (type instanceof GraphQLObjectType) {
            return ImmutableSet.of((GraphQLObjectType) type);
        } else if (type instanceof GraphQLInterfaceType) {
            return ImmutableSet.copyOf(graphQLSchema.getImplementations((GraphQLInterfaceType) type));
        } else if (type instanceof GraphQLUnionType) {
            List<GraphQLNamedOutputType> unionTypes = ((GraphQLUnionType) type).getTypes();
            return ImmutableSet.copyOf(ImmutableKit.map(unionTypes, GraphQLObjectType.class::cast));
        } else {
            return assertShouldNeverHappen();
        }
    }
}
