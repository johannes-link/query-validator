package org.hibernate.query.validator;

import org.hibernate.CustomEntityDirtinessStrategy;
import org.hibernate.MappingException;
import org.hibernate.QueryException;
import org.hibernate.TypeHelper;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.internal.DisabledCaching;
import org.hibernate.cache.spi.CacheImplementor;
import org.hibernate.cfg.Settings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.dialect.function.SQLFunctionRegistry;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.graph.spi.RootGraphImplementor;
import org.hibernate.hql.internal.ast.ParseErrorHandler;
import org.hibernate.internal.TypeLocatorImpl;
import org.hibernate.metamodel.internal.MetamodelImpl;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.EntityNotFoundDelegate;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.type.*;
import org.hibernate.type.spi.TypeConfiguration;
import org.hibernate.usertype.UserType;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static org.hibernate.internal.util.StringHelper.isEmpty;

public abstract class MockSessionFactory implements SessionFactoryImplementor {

    static final SessionFactoryOptions OPTIONS = Mocker.make(MockSessionFactoryOptions.class);

    private static final SQLFunction UNKNOWN_SQL_FUNCTION = new UnknownSQLFunction();

    static final CustomType UNKNOWN_TYPE = new CustomType(Mocker.make(UserType.class));

    private final Map<String,MockEntityPersister> entityPersistersByName = new HashMap<>();
    private final Map<String,MockCollectionPersister> collectionPersistersByName = new HashMap<>();
    private final Set<String> unknownFunctions = new HashSet<>();
    private final List<String> functionWhitelist;

    private static final TypeConfiguration typeConfiguration = new TypeConfiguration();

    @SuppressWarnings("deprecation")
    static final TypeResolver typeResolver =
            new TypeResolver(typeConfiguration,
                    new TypeFactory(typeConfiguration));

    static final TypeHelper typeHelper = new TypeLocatorImpl(typeResolver);

    static final StandardServiceRegistryImpl serviceRegistry =
            new StandardServiceRegistryImpl(
                    new BootstrapServiceRegistryBuilder().build(),
                    singletonList(MockJdbcServicesInitiator.INSTANCE),
                    emptyList(),
                    emptyMap());

    private final MetamodelImplementor metamodel =
            new MetamodelImpl(MockSessionFactory.this, typeConfiguration) {
                @Override
                public String getImportedClassName(String className) {
                    return className;
                }

                @Override
                public EntityPersister entityPersister(String entityName)
                        throws MappingException {
                    return createEntityPersister(entityName);
                }

                @Override
                public EntityPersister locateEntityPersister(String entityName)
                        throws MappingException {
                    return createEntityPersister(entityName);
                }

                @Override
                public CollectionPersister collectionPersister(String role) {
                    return createCollectionPersister(role);
                }
            };

    private ParseErrorHandler handler;

    public MockSessionFactory(List<String> functionWhitelist, ParseErrorHandler handler) {
        this.functionWhitelist = functionWhitelist;
        this.handler = handler;
    }

    static CollectionType createCollectionType(String role, String name) {
        @SuppressWarnings("deprecation")
        TypeFactory typeFactory = typeResolver.getTypeFactory();
        switch (name) {
            case "Set":
            case "SortedSet":
                //might actually be a bag!
                //TODO: look for @OrderColumn on the property
                return typeFactory.set(role, null);
            case "List":
            case "SortedList":
                return typeFactory.list(role, null);
            case "Map":
            case "SortedMap":
                return typeFactory.map(role, null);
            default:
                return typeFactory.bag(role, null);
        }
    }

    /**
     * Lazily create a {@link MockEntityPersister}
     */
    abstract MockEntityPersister createMockEntityPersister(String entityName);

    /**
     * Lazily create a {@link MockCollectionPersister}
     */
    abstract MockCollectionPersister createMockCollectionPersister(String role);

    abstract boolean isClassDefined(String qualifiedName);

    abstract boolean isFieldDefined(String qualifiedClassName, String fieldName);

    abstract boolean isConstructorDefined(String qualifiedClassName, List<Type> argumentTypes);

    private EntityPersister createEntityPersister(String entityName) {
        MockEntityPersister result = entityPersistersByName.get(entityName);
        if (result!=null) return result;
        result = createMockEntityPersister(entityName);
        entityPersistersByName.put(entityName, result);
        return result;
    }

    private CollectionPersister createCollectionPersister(String entityName) {
        MockCollectionPersister result = collectionPersistersByName.get(entityName);
        if (result!=null) return result;
        result = createMockCollectionPersister(entityName);
        collectionPersistersByName.put(entityName, result);
        return result;
    }

    List<MockEntityPersister> getMockEntityPersisters() {
        return entityPersistersByName.values()
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("deprecation")
    @Override
    public TypeResolver getTypeResolver() {
        return typeResolver;
    }

    @Override
    public Type getIdentifierType(String className)
            throws MappingException {
        return createEntityPersister(className)
                .getIdentifierType();
    }

    @Override
    public String getIdentifierPropertyName(String className)
            throws MappingException {
        return createEntityPersister(className)
                .getIdentifierPropertyName();
    }

    @Override
    public Type getReferencedPropertyType(String className, String propertyName)
            throws MappingException {
        return createEntityPersister(className)
                .getPropertyType(propertyName);
    }

    @Override
    public MetamodelImplementor getMetamodel() {
        return metamodel;
    }

    @Override
    public ServiceRegistryImplementor getServiceRegistry() {
        return serviceRegistry;
    }

    @Override
    public JdbcServices getJdbcServices() {
        return serviceRegistry.getService(JdbcServices.class);
    }

    @Override
    public String getUuid() {
        return OPTIONS.getUuid();
    }

    @Override
    public String getName() {
        return OPTIONS.getSessionFactoryName();
    }

    @Override
    public SessionFactoryOptions getSessionFactoryOptions() {
        return OPTIONS;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Settings getSettings() {
        return new Settings(OPTIONS);
    }

    @Override
    public Set getDefinedFilterNames() {
        return emptySet();
    }

    @Override
    public CacheImplementor getCache() {
        return new DisabledCaching(this);
    }

    @Override
    public TypeHelper getTypeHelper() {
        return typeHelper;
    }

    @Override
    public EntityNotFoundDelegate getEntityNotFoundDelegate() {
        return OPTIONS.getEntityNotFoundDelegate();
    }

    @Override
    public CustomEntityDirtinessStrategy getCustomEntityDirtinessStrategy() {
        return OPTIONS.getCustomEntityDirtinessStrategy();
    }

    @Override
    public CurrentTenantIdentifierResolver getCurrentTenantIdentifierResolver() {
        return OPTIONS.getCurrentTenantIdentifierResolver();
    }

    @Override
    public SQLFunctionRegistry getSqlFunctionRegistry() {
        return new SQLFunctionRegistry(getJdbcServices().getDialect(),
                OPTIONS.getCustomSqlFunctionMap()) {
            @Override
            public SQLFunction findSQLFunction(String functionName) {
                if (isEmpty(functionName)) {
                    return null;
                }
                SQLFunction sqlFunction = super.findSQLFunction(functionName);
                if (sqlFunction==null) {
                    if (!functionWhitelist.contains(functionName)
                            && unknownFunctions.add(functionName)) {
                        handler.reportWarning(functionName
                                + " is not defined (add it to whitelist)");
                    }
                    return UNKNOWN_SQL_FUNCTION;
                }
                else {
                    return sqlFunction;
                }
            }
        };
    }

    @Override
    public void close() {}

    @Override
    public RootGraphImplementor<?> findEntityGraphByName(String s) {
        throw new UnsupportedOperationException();
    }

    private static class UnknownSQLFunction implements SQLFunction {
        @Override
        public boolean hasArguments() {
            return true;
        }

        @Override
        public boolean hasParenthesesIfNoArguments() {
            return true;
        }

        @Override
        public Type getReturnType(Type firstArgumentType, Mapping mapping) throws QueryException {
            return FloatType.INSTANCE; // ¯\_(ツ)_/¯
        }

        @Override
        public String render(Type firstArgumentType, List arguments, SessionFactoryImplementor factory) throws QueryException {
            throw new UnsupportedOperationException();
        }
    }
}
