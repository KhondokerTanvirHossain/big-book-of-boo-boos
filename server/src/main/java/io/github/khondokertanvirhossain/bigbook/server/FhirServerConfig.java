package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.config.ThreadPoolFactoryConfig;
import ca.uhn.fhir.jpa.api.IDaoRegistry;
import ca.uhn.fhir.jpa.api.dao.DaoRegistrationService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.SearchConfig;
import ca.uhn.fhir.jpa.config.r4.JpaR4Config;
import ca.uhn.fhir.jpa.config.util.HapiEntityManagerFactoryUtil;
import ca.uhn.fhir.jpa.config.util.ResourceCountCacheUtil;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.provider.DaoRegistryResourceSupportedSvc;
import ca.uhn.fhir.jpa.provider.IJpaSystemProvider;
import ca.uhn.fhir.jpa.provider.JpaCapabilityStatementProvider;
import ca.uhn.fhir.jpa.provider.ValueSetOperationProvider;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.util.ResourceCountCache;
import ca.uhn.fhir.rest.api.IResourceSupportedSvc;
import ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaValidator;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.github.khondokertanvirhossain.bigbook.server.policy.AccessPolicyProvider;
import io.github.khondokertanvirhossain.bigbook.server.policy.AccessPolicyStore;
import io.github.khondokertanvirhossain.bigbook.server.policy.CriteriaEvaluator;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyBinder;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyAuthorizationInterceptor;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyDenialLog;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyEnforcementInterceptor;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyWriteInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ResourceProviderFactory;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Meta;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Embeds HAPI FHIR JPA in this JVM (ADR-006). Wiring only: every behaviour is HAPI's,
 * and Hibernate settings live in application.yml under spring.jpa.properties.
 */
@Configuration
@Import({
    JpaR4Config.class,
    SearchConfig.class,
    ThreadPoolFactoryConfig.class,
    JpaBatch2Config.class,
    Batch2JobsConfig.class,
    // HAPI's in-JVM message channels, which batch2 requires; subscription delivery itself is issue #12 (ADR-002)
    SubscriptionChannelConfig.class
})
public class FhirServerConfig {

    /** Medplum's FHIR base path (BB-R-014.1). */
    public static final String FHIR_PATH = "/fhir/R4";

    /**
     * The datastore contract (issue #8, BB-R-001). Configuration only: every behaviour below is HAPI's,
     * switched to what Medplum's contract requires, never to reproduce a Medplum defect.
     */
    @Bean
    public JpaStorageSettings jpaStorageSettings() {
        JpaStorageSettings settings = new JpaStorageSettings();
        // server-assigned UUIDs, as Medplum does (BB-R-001.9, T? REPO-002); HAPI's default is sequential numbers
        settings.setResourceServerIdStrategy(JpaStorageSettings.IdStrategyEnum.UUID);
        // a client id is accepted only to resolve urn:uuid inside a bundle, never on a plain create (D13)
        settings.setResourceClientIdStrategy(JpaStorageSettings.ClientIdStrategyEnum.NOT_ALLOWED);
        // a conditional delete that matches many is 412, not a mass delete (T19)
        settings.setAllowMultipleDelete(false);
        // BB-R-001.6 / D16: on by default, so a reference into another project is refused on write
        settings.setEnforceReferentialIntegrityOnWrite(true);
        settings.setEnforceReferentialIntegrityOnDelete(true);
        return settings;
    }

    /** One project is one partition (issue #4). References across partitions stay HAPI's default: not allowed. */
    @Bean
    public PartitionSettings partitionSettings() {
        PartitionSettings settings = new PartitionSettings();
        settings.setPartitioningEnabled(true);
        // A conditional create's match URL is recorded to catch concurrent duplicates. HAPI's default records
        // it WITHOUT the partition, so `Practitioner?identifier=x` in project A blocks the same conditional
        // create in project B — two tenants colliding on one email (found inviting the same person to two
        // projects, issue #6). Partition-scoped is the only correct setting when a partition is a tenant.
        settings.setConditionalCreateDuplicateIdentifiersEnabled(true);
        return settings;
    }

    @Bean
    public DaoRegistry daoRegistry(FhirContext fhirContext, ApplicationContext applicationContext) {
        return new ContextBackedDaoRegistry(fhirContext, applicationContext);
    }

    @Bean
    public DaoRegistrationService daoRegistrationService(DaoRegistry daoRegistry) {
        return new DaoRegistrationService(daoRegistry);
    }

    @Bean
    public IResourceSupportedSvc resourceSupportedSvc(IDaoRegistry daoRegistry) {
        return new DaoRegistryResourceSupportedSvc(daoRegistry);
    }

    /** Looked up by this name from HAPI's CapabilityStatement provider. */
    @Bean(name = "myResourceCountsCache")
    public ResourceCountCache resourceCountsCache(DaoRegistry daoRegistry) {
        return ResourceCountCacheUtil.newResourceCountCache(daoRegistry);
    }

    @Bean
    public DatabaseBackedPagingProvider databaseBackedPagingProvider() {
        DatabaseBackedPagingProvider paging = new DatabaseBackedPagingProvider();
        // _count cap 1000, inclusive, as the SDK expects (T45); page sizes are BB-R-002's, issue #9
        paging.setDefaultPageSize(20);
        paging.setMaximumPageSize(1000);
        return paging;
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            JpaProperties jpaProperties,
            ConfigurableListableBeanFactory beanFactory,
            FhirContext fhirContext,
            JpaStorageSettings storageSettings) {
        LocalContainerEntityManagerFactoryBean factory =
                HapiEntityManagerFactoryUtil.newEntityManagerFactory(beanFactory, fhirContext, storageSettings);
        factory.setPersistenceUnitName("HAPI_PU");
        factory.setDataSource(dataSource);
        factory.setJpaPropertyMap(jpaProperties.getProperties());
        return factory;
    }

    @Bean
    @Primary
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    public RestfulServer fhirServer(
            BigBookProperties properties,
            IFhirSystemDao<Bundle, Meta> systemDao,
            IJpaSystemProvider systemProvider,
            ResourceProviderFactory resourceProviders,
            DaoRegistry daoRegistry,
            JpaStorageSettings storageSettings,
            ISearchParamRegistry searchParamRegistry,
            IValidationSupport validationSupport,
            DatabaseBackedPagingProvider pagingProvider,
            ValueSetOperationProvider valueSetOperationProvider,
            TenantStore tenantStore,
            PartitionSettings partitionSettings,
            InMemoryResourceMatcher inMemoryResourceMatcher,
            PolicyBinder policyBinder,
            AccessPolicyProvider accessPolicyProvider) {
        RestfulServer server = new RestfulServer(systemDao.getContext());
        daoRegistry.setSupportedResourceTypes(systemDao.getContext().getResourceTypes());
        server.registerProviders(resourceProviders.createProviders());
        server.registerProvider(systemProvider);
        // terminology operations on ValueSet; $lookup and $validate-code come with the generated providers
        server.registerProvider(valueSetOperationProvider);
        // AccessPolicy is served from Big Book's own table, not HAPI JPA: the @ResourceDef spike on #7
        // measured that JPA has no DAO for a runtime-registered type (HAPI-0572), and the failure is
        // structural. ADR-003's other Medplum admin types will follow this same pattern in v0.2.
        server.registerProvider(accessPolicyProvider);
        server.setServerConformanceProvider(new JpaCapabilityStatementProvider(
                server, systemDao, storageSettings, searchParamRegistry, validationSupport));
        server.setPagingProvider(pagingProvider);
        server.registerInterceptor(new PartitionInterceptor(tenantStore, partitionSettings));
        server.registerInterceptor(new InterimOperationDenyInterceptor());
        // The policy layer (ADR-001), registered in the order it must run: the coarse type × interaction
        // rules first, so an entirely unpermitted interaction is a 403 before any resource is fetched; then
        // the criteria hooks, which narrow, drop and hide what the coarse layer let through; then the write
        // check. Partition scoping stays ahead of all of it — a policy is only ever asked about resources
        // that are already confined to the caller's project.
        PolicyDenialLog denialLog = new PolicyDenialLog();
        CriteriaEvaluator criteriaEvaluator = new CriteriaEvaluator(inMemoryResourceMatcher);
        // the binder first: every hook below reads the policy it resolves, and a request that reaches them
        // without one is refused rather than allowed
        server.registerInterceptor(policyBinder);
        server.registerInterceptor(new PolicyAuthorizationInterceptor(denialLog));
        server.registerInterceptor(new PolicyEnforcementInterceptor(criteriaEvaluator, denialLog));
        server.registerInterceptor(new PolicyWriteInterceptor(criteriaEvaluator, denialLog));
        server.setServerAddressStrategy(new HardcodedServerAddressStrategy(properties.fhirBaseUrl()));
        return server;
    }

    @Bean
    public CriteriaValidator criteriaValidator(FhirContext fhirContext, InMemoryResourceMatcher matcher) {
        return new CriteriaValidator(fhirContext, matcher);
    }

    @Bean
    public PolicyCompiler policyCompiler(CriteriaValidator criteriaValidator, MatchUrlService matchUrlService) {
        return new PolicyCompiler(criteriaValidator, matchUrlService);
    }

    @Bean
    public AccessPolicyStore accessPolicyStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new AccessPolicyStore(jdbcClient, objectMapper);
    }

    @Bean
    public AccessPolicyProvider accessPolicyProvider(AccessPolicyStore store, CriteriaValidator criteriaValidator,
            ObjectMapper objectMapper, FhirContext fhirContext) {
        return new AccessPolicyProvider(store, criteriaValidator, objectMapper, fhirContext);
    }

    @Bean
    public PolicyBinder policyBinder(PolicyCompiler policyCompiler, AccessPolicyStore accessPolicyStore, ObjectMapper objectMapper) {
        return new PolicyBinder(policyCompiler, accessPolicyStore, objectMapper);
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> fhirServlet(RestfulServer fhirServer) {
        ServletRegistrationBean<RestfulServer> registration =
                new ServletRegistrationBean<>(fhirServer, FHIR_PATH + "/*");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
