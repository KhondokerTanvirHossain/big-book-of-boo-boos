package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.khondokertanvirhossain.bigbook.core.TenantProvisioner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class BootstrapRunnerTest {

    private final TenantProvisioner provisioner = mock(TenantProvisioner.class);
    private final List<Duration> pauses = new ArrayList<>();
    private final TenantProvisioner.Project project = new TenantProvisioner.Project(UUID.randomUUID(), 1, "Super Admin", "active");

    private BootstrapRunner runner(String email, String password) {
        return new BootstrapRunner(new BigBookProperties.Admin(email, password), provisioner, Duration.ofSeconds(20), pauses::add);
    }

    @Test
    void aMissingEmailFailsStartupAndSaysWhichVariable() {
        assertThatThrownBy(() -> runner(" ", null).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BIGBOOK_ADMIN_EMAIL is not set");
        verify(provisioner, never()).ensureSuperAdminProject(anyString());
    }

    @Test
    void medplumsDefaultPasswordIsRefused() {
        assertThatThrownBy(() -> runner("root@bigbook.test", "medplum_admin").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("medplum_admin");
        verify(provisioner, never()).ensureSuperAdminProject(anyString());
    }

    @Test
    void anUnsetPasswordIsGeneratedNotDefaulted() throws Exception {
        when(provisioner.ensureSuperAdminProject(anyString())).thenReturn(project);
        ArgumentCaptor<String> passwords = ArgumentCaptor.forClass(String.class);

        runner("root@bigbook.test", "").run(new DefaultApplicationArguments());
        runner("root@bigbook.test", null).run(new DefaultApplicationArguments());

        verify(provisioner, times(2)).ensureAdminMembership(eq(project), eq("root@bigbook.test"), passwords.capture(), eq(true), any());
        assertThat(passwords.getAllValues()).hasSize(2).doesNotHaveDuplicates().allSatisfy(password -> assertThat(password).hasSizeGreaterThanOrEqualTo(32));
    }

    @Test
    void keycloakNotBeingUpYetIsRetriedWithBackoff() throws Exception {
        when(provisioner.ensureSuperAdminProject(anyString()))
                .thenThrow(new RuntimeException("connection refused"))
                .thenThrow(new RuntimeException("503"))
                .thenReturn(project);

        runner("root@bigbook.test", "a-chosen-password").run(new DefaultApplicationArguments());

        assertThat(pauses).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
        verify(provisioner).ensureAdminMembership(eq(project), anyString(), eq("a-chosen-password"), anyBoolean(), any());
    }

    @Test
    void aBootstrapThatNeverCompletesFailsStartupInsteadOfHanging() {
        when(provisioner.ensureSuperAdminProject(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> runner("root@bigbook.test", "a-chosen-password").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete")
                .hasRootCauseMessage("connection refused");
        assertThat(pauses).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(15));
    }
}
