package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.context.ApplicationContext;

/**
 * HAPI's DaoRegistry no longer finds DAOs by itself, and HAPI's own beans look DAOs up while the
 * context is still starting. This fills the registry from the context on first lookup, as
 * hapi-fhir-jpaserver-starter's JpaStarterDaoRegistry does.
 */
class ContextBackedDaoRegistry extends DaoRegistry {

    private final FhirContext fhirContext;
    private final ApplicationContext applicationContext;
    private boolean initialized;

    ContextBackedDaoRegistry(FhirContext fhirContext, ApplicationContext applicationContext) {
        super(fhirContext);
        this.fhirContext = fhirContext;
        this.applicationContext = applicationContext;
    }

    @Override
    public <R extends IBaseResource, D extends IFhirResourceDao<R>> D getResourceDaoOrNull(Class<R> resourceType) {
        initializeIfNeeded();
        return super.getResourceDaoOrNull(resourceType);
    }

    @Override
    public <R extends IBaseResource, D extends IFhirResourceDao<R>> D getResourceDaoOrNull(String resourceName) {
        initializeIfNeeded();
        return super.getResourceDaoOrNull(resourceName);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void register(IFhirResourceDao resourceDao) {
        String name = fhirContext.getResourceDefinition(resourceDao.getResourceType()).getName();
        if (super.getResourceDaoOrNull(name) == null) {
            super.register(resourceDao);
        }
    }

    private void initializeIfNeeded() {
        if (!initialized) {
            // flag set last, as upstream does: a lookup that re-enters during startup must still scan the context
            applicationContext.getBeansOfType(IFhirResourceDao.class).values().forEach(this::register);
            initialized = true;
        }
    }
}
