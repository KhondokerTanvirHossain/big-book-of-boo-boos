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
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.util.ResourceCountCache;
import ca.uhn.fhir.rest.api.IResourceSupportedSvc;
import ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ResourceProviderFactory;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
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

    @Bean
    public JpaStorageSettings jpaStorageSettings() {
        return new JpaStorageSettings();
    }

    @Bean
    public PartitionSettings partitionSettings() {
        return new PartitionSettings();
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
        return new DatabaseBackedPagingProvider();
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
            DatabaseBackedPagingProvider pagingProvider) {
        RestfulServer server = new RestfulServer(systemDao.getContext());
        daoRegistry.setSupportedResourceTypes(systemDao.getContext().getResourceTypes());
        server.registerProviders(resourceProviders.createProviders());
        server.registerProvider(systemProvider);
        server.setServerConformanceProvider(new JpaCapabilityStatementProvider(
                server, systemDao, storageSettings, searchParamRegistry, validationSupport));
        server.setPagingProvider(pagingProvider);
        server.setServerAddressStrategy(new HardcodedServerAddressStrategy(properties.fhirBaseUrl()));
        return server;
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> fhirServlet(RestfulServer fhirServer) {
        ServletRegistrationBean<RestfulServer> registration =
                new ServletRegistrationBean<>(fhirServer, FHIR_PATH + "/*");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
