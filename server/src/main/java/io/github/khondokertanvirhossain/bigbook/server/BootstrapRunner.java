package io.github.khondokertanvirhossain.bigbook.server;

import io.github.khondokertanvirhossain.bigbook.core.tenant.Project;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantProvisioner;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.StringUtils;

/**
 * First-boot bootstrap (BB-R-011.4, BB-R-005.7): the super-admin project and its super-admin user.
 * Idempotent, so it runs on every start. Keycloak may not be up yet, or may be restarting, so it
 * retries; the server reports ready only after this returns, and a start that cannot finish fails.
 */
public class BootstrapRunner implements ApplicationRunner {

    /** Medplum's shipped default; refused so that a copied Medplum config cannot become the password (D34). */
    static final String REFUSED_PASSWORD = "medplum_admin";

    static final String SUPER_ADMIN_PROJECT_NAME = "Super Admin";

    static final int RECONCILE_OLDER_THAN_MINUTES = 5;

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    /** Pauses between attempts; interruptible so tests need not wait. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final BigBookProperties.Admin admin;
    private final TenantProvisioner provisioner;
    private final Duration giveUpAfter;
    private final Sleeper sleeper;

    public BootstrapRunner(BigBookProperties.Admin admin, TenantProvisioner provisioner) {
        this(admin, provisioner, Duration.ofMinutes(5), duration -> Thread.sleep(duration.toMillis()));
    }

    BootstrapRunner(BigBookProperties.Admin admin, TenantProvisioner provisioner, Duration giveUpAfter, Sleeper sleeper) {
        this.admin = admin;
        this.provisioner = provisioner;
        this.giveUpAfter = giveUpAfter;
        this.sleeper = sleeper;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        if (!StringUtils.hasText(admin.email())) {
            throw new IllegalStateException(
                    "BIGBOOK_ADMIN_EMAIL is not set. Big Book creates its super-admin from it on first boot and has no default.");
        }
        if (REFUSED_PASSWORD.equals(admin.password())) {
            throw new IllegalStateException(
                    "BIGBOOK_ADMIN_PASSWORD is Medplum's default, 'medplum_admin'. Choose another, or leave it unset to have one generated.");
        }
        boolean generated = !StringUtils.hasText(admin.password());
        String password = generated ? generatePassword() : admin.password();

        Duration waited = Duration.ZERO;
        Duration pause = Duration.ofSeconds(2);
        while (true) {
            try {
                Project project = provisioner.ensureSuperAdminProject(SUPER_ADMIN_PROJECT_NAME);
                provisioner.ensureAdminMembership(project, admin.email(), password, true, () -> {
                    if (generated) {
                        // the one time this is ever shown (BB-R-005.7); not stored anywhere by Big Book
                        log.warn("Generated super-admin password for {}: {}", admin.email(), password);
                    }
                });
                log.info("Bootstrap complete: super-admin project {} ({})", project.id(), admin.email());
                // ADR-007: finish creates an earlier run left half done; anything younger may still be in flight elsewhere
                provisioner.reconcile(RECONCILE_OLDER_THAN_MINUTES);
                return;
            } catch (RuntimeException failure) {
                if (waited.compareTo(giveUpAfter) >= 0) {
                    throw new IllegalStateException("Bootstrap did not complete within " + giveUpAfter, failure);
                }
                log.warn("Bootstrap attempt failed, retrying in {} s: {}", pause.toSeconds(), failure.toString());
                sleeper.sleep(pause);
                waited = waited.plus(pause);
                pause = pause.multipliedBy(2).compareTo(Duration.ofSeconds(15)) > 0 ? Duration.ofSeconds(15) : pause.multipliedBy(2);
            }
        }
    }

    private static String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
