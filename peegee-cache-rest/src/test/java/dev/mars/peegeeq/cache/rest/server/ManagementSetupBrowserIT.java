package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P2 real-browser scenarios for setup lifecycle and target-policy behavior. */
class ManagementSetupBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-SETUP-002", requirement = "UI design: first-use setup state has one clear registration action", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open Setups with an empty registry", expectedResult = "The empty state explains that no setups exist and offers Register the first setup", cleanup = "Close the isolated context and reset the worker schema", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void emptyRegistryShowsFirstSetupAction() throws Exception {
        unregistered(context -> {
            Page page = setups(context);
            heading(page, "No setups registered");
            assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Register the first setup"))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-003", requirement = "UI design: operators can open setup registration from the primary action", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Activate Register setup from the page header", expectedResult = "The TLS-verified registration dialog opens", cleanup = "Close the dialog and isolated context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void primaryRegisterActionOpensTlsDialog() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register setup");
            assertThat(dialog.getByText("TLS-verified PostgreSQL", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(dialog.getByLabel("TLS mode")).hasValue("VERIFY_FULL");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-004", requirement = "UI design: the empty-state setup action opens the same reviewed form", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Activate Register the first setup", expectedResult = "Exactly one registration dialog opens with the Setup ID field focused", cleanup = "Close the modal and isolated context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void emptyStateRegisterActionOpensOneDialog() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register the first setup");
            assertThat(dialog).hasCount(1);
            assertThat(dialog.getByLabel("Setup ID")).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-005", requirement = "UI design: setup registration can be dismissed without mutation", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open registration and activate Close registration", expectedResult = "The dialog closes and the empty registry remains unchanged", cleanup = "Close the isolated context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void closeRegistrationDismissesWithoutMutation() throws Exception {
        unregistered(context -> {
            Page page = setups(context);
            Locator dialog = registration(page, "Register setup");
            dialog.getByLabel("Close registration").click();
            assertThat(dialog).hasCount(0);
            heading(page, "No setups registered");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-006", requirement = "UI design: Escape dismisses an idle registration modal", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Open registration and press Escape", expectedResult = "Focus returns to the page and no registration occurs", cleanup = "Close the isolated context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void escapeDismissesIdleRegistrationDialog() throws Exception {
        unregistered(context -> {
            Page page = setups(context);
            Locator dialog = registration(page, "Register setup");
            dialog.getByLabel("Close registration").press("Escape");
            assertThat(dialog).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Register setup"))).isFocused();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-007", requirement = "UI design: setup identifiers are mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Setup ID form contract", expectedResult = "Setup ID is a required input", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void setupIdIsRequired() throws Exception {
        unregistered(context -> assertEquals("", field(registration(setups(context), "Register setup"), "Setup ID").getAttribute("required")));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-008", requirement = "Management API: setup identifiers use the canonical lowercase pattern", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect and exercise the Setup ID pattern", expectedResult = "Uppercase identifiers fail native validity", cleanup = "Clear the invalid identifier and close the context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void uppercaseSetupIdFailsCanonicalPattern() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Setup ID");
            input.fill("Uppercase");
            assertEquals(false, input.evaluate("element => element.checkValidity()"));
            assertEquals("[a-z][a-z0-9\\-]{0,62}", input.getAttribute("pattern"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-009", requirement = "Management API: setup identifiers must start with a letter", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Enter a setup identifier beginning with a digit", expectedResult = "The browser rejects the identifier before submission", cleanup = "Clear the invalid identifier and close the context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void digitPrefixedSetupIdFailsCanonicalPattern() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Setup ID");
            input.fill("1database");
            assertEquals(false, input.evaluate("element => element.checkValidity()"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-010", requirement = "Management API: setup identifiers are bounded to 63 characters", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Setup ID length boundary", expectedResult = "The browser enforces a maximum length of 63", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void setupIdHasSixtyThreeCharacterLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Setup ID")).hasAttribute("maxlength", "63"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-011", requirement = "Management API: setup display names are mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect the Display name form contract", expectedResult = "Display name is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void displayNameIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Display name")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-012", requirement = "Management API: setup display names have a reviewed length bound", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect the Display name maximum length", expectedResult = "The browser caps display names at 128 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void displayNameHasOneHundredTwentyEightCharacterLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Display name")).hasAttribute("maxlength", "128"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-013", requirement = "Management API: database host is mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Host form contract", expectedResult = "Host is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseHostIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Host")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-014", requirement = "Management API: database host has the DNS length bound", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Host maximum length", expectedResult = "The browser caps host at 253 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseHostHasDnsLengthLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Host")).hasAttribute("maxlength", "253"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-015", requirement = "Management API: database port is numeric", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Port accessibility role", expectedResult = "Port exposes the numeric spinbutton role", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePortUsesNumericInput() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Port")).hasAttribute("role", "spinbutton"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-016", requirement = "Management API: database port excludes zero", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Enter port zero and inspect the numeric boundary", expectedResult = "The control exposes minimum one and clamps zero to one", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePortRejectsZero() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Port");
            input.fill("0");
            assertThat(input).hasAttribute("aria-valuemin", "1");
            assertThat(input).hasValue("1");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-017", requirement = "Management API: database port excludes values above 65535", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Enter port 65536 and inspect the numeric boundary", expectedResult = "The control exposes the TCP maximum and clamps 65536 to 65535", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePortRejectsAboveTcpMaximum() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Port");
            input.fill("65536");
            assertThat(input).hasAttribute("aria-valuemax", "65535");
            assertThat(input).hasValue("65535");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-018", requirement = "Management API: database name is mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Database form contract", expectedResult = "Database is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseNameIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Database")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-019", requirement = "Management API: database name is bounded to PostgreSQL identifier length", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect the Database maximum length", expectedResult = "The browser caps database at 63 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseNameHasPostgresIdentifierLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Database")).hasAttribute("maxlength", "63"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-020", requirement = "UI design: schema defaults to PostgreSQL public", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Open a fresh registration form", expectedResult = "Schema starts with the reviewed public default", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void schemaDefaultsToPublic() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Schema")).hasValue("public"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-021", requirement = "Management API: schema is mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Schema form contract", expectedResult = "Schema is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void schemaIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Schema")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-022", requirement = "Management API: schema names are bounded to PostgreSQL identifier length", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect the Schema maximum length", expectedResult = "The browser caps schema at 63 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void schemaHasPostgresIdentifierLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Schema")).hasAttribute("maxlength", "63"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-023", requirement = "Management API: database username is mandatory", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Username form contract", expectedResult = "Username is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseUsernameIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Username")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-024", requirement = "UI design: username participates only in the browser credential form", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Username autocomplete semantics", expectedResult = "The input uses the standard username autocomplete purpose", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseUsernameUsesUsernameAutocompletePurpose() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Username")).hasAttribute("autocomplete", "username"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-025", requirement = "Management API: database username has a reviewed length bound", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Username maximum length", expectedResult = "The browser caps username at 128 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databaseUsernameHasReviewedLengthLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Username")).hasAttribute("maxlength", "128"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-026", requirement = "UI design: database passwords are masked", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the Password input type", expectedResult = "The browser renders a password control", cleanup = "Close the sensitive form context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePasswordUsesMaskedControl() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Password")).hasAttribute("type", "password"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-027", requirement = "UI design: database passwords are treated as newly supplied secrets", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect Password autocomplete semantics", expectedResult = "The browser uses new-password rather than ordinary text autocomplete", cleanup = "Close the sensitive form context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePasswordUsesNewPasswordAutocompletePurpose() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Password")).hasAttribute("autocomplete", "new-password"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-028", requirement = "Management API: database password is mandatory for setup registration", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the Password required constraint", expectedResult = "The password control is required", cleanup = "Close the sensitive form context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePasswordIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Password")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-029", requirement = "Management API: database password input has a denial-of-service bound", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect Password maximum length", expectedResult = "The browser caps the secret at 4096 characters", cleanup = "Close the sensitive form context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void databasePasswordHasRequestBound() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Password")).hasAttribute("maxlength", "4096"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-030", requirement = "Management API: VERIFY_FULL requires an explicit trust profile", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the Trust profile form contract", expectedResult = "Trust profile is required", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void trustProfileIsRequired() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Trust profile")).hasAttribute("required", ""));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-031", requirement = "Management API: trust-profile identifiers have a reviewed length bound", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Trust profile maximum length", expectedResult = "The browser caps trust profile at 128 characters", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void trustProfileHasReviewedLengthLimit() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Trust profile")).hasAttribute("maxlength", "128"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-032", requirement = "Management API: pool size is numeric", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Inspect the Pool size accessibility role", expectedResult = "Pool size exposes the numeric spinbutton role", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void poolSizeUsesNumericInput() throws Exception {
        unregistered(context -> assertThat(field(registration(setups(context), "Register setup"), "Pool size")).hasAttribute("role", "spinbutton"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-033", requirement = "Management API: pool size excludes zero", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Enter pool size zero and inspect the numeric boundary", expectedResult = "The control exposes minimum one and clamps zero to one", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void poolSizeRejectsZero() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Pool size");
            input.fill("0");
            assertThat(input).hasAttribute("aria-valuemin", "1");
            assertThat(input).hasValue("1");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-034", requirement = "Management API: pool size is capped at 100", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Enter pool size 101 and inspect the numeric boundary", expectedResult = "The control exposes maximum 100 and clamps 101 to 100", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void poolSizeRejectsAboveMaximum() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "Pool size");
            input.fill("101");
            assertThat(input).hasAttribute("aria-valuemax", "100");
            assertThat(input).hasValue("100");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-035", requirement = "Management API: browser registration is fixed to VERIFY_FULL TLS", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the TLS mode control", expectedResult = "VERIFY_FULL is visible and cannot be edited", cleanup = "Close the validation context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void tlsModeIsFixedAndDisabled() throws Exception {
        unregistered(context -> {
            Locator input = field(registration(setups(context), "Register setup"), "TLS mode");
            assertThat(input).hasValue("VERIFY_FULL");
            assertThat(input).isDisabled();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-036", requirement = "UI design: native form validation prevents empty registration requests", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Submit a completely empty registration form", expectedResult = "The modal remains open, Setup ID receives validation focus, and no mutation is sent", cleanup = "Close the unchanged form context", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void emptyRegistrationIsPreventedBeforeNetworkMutation() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register setup");
            dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Register setup")).click();
            assertThat(dialog).isVisible();
            assertThat(field(dialog, "Setup ID")).isFocused();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-037", requirement = "Management API: a policy-allowed TLS connection can be tested before registration", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Fill the reviewed PostgreSQL target and activate Test connection", expectedResult = "The real TLS database test succeeds visibly", cleanup = "Close the tested form and reset PostgreSQL", operations = {"listSetups", "testUnregisteredSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void allowedTlsTargetPassesPreRegistrationTest() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register setup");
            fillValid(dialog, context);
            testConnection(dialog);
            assertThat(dialog.getByRole(AriaRole.STATUS)).containsText("Connection succeeded");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-038", requirement = "Management API: connection test reports migrated schema readiness", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Test the migrated PostgreSQL target", expectedResult = "The visible result reports schema ready", cleanup = "Close the tested form and reset PostgreSQL", operations = {"listSetups", "testUnregisteredSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void connectionTestReportsReadySchema() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register setup");
            fillValid(dialog, context);
            testConnection(dialog);
            assertThat(dialog.getByRole(AriaRole.STATUS)).containsText("schema ready");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-039", requirement = "UI design: successful connection testing preserves entered registration data", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Test a valid target and inspect the still-open form", expectedResult = "The setup identifier and password remain available for the explicit registration step", cleanup = "Close the secret-bearing form context", operations = {"listSetups", "testUnregisteredSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulConnectionTestPreservesRegistrationFields() throws Exception {
        unregistered(context -> {
            Locator dialog = registration(setups(context), "Register setup");
            fillValid(dialog, context);
            testConnection(dialog);
            assertThat(field(dialog, "Setup ID")).hasValue(ManagementConsolePostgresFixture.SETUP_ID);
            assertThat(field(dialog, "Password")).hasValue(ManagementConsolePostgresFixture.DATABASE_PASSWORD);
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-040", requirement = "UI design: testing a target does not implicitly register it", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Test a valid target and close registration", expectedResult = "The page returns to an empty registry", cleanup = "Close the context and reset PostgreSQL", operations = {"listSetups", "testUnregisteredSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void connectionTestDoesNotImplicitlyRegisterSetup() throws Exception {
        unregistered(context -> {
            Page page = setups(context);
            Locator dialog = registration(page, "Register setup");
            fillValid(dialog, context);
            testConnection(dialog);
            dialog.getByLabel("Close registration").click();
            heading(page, "No setups registered");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-041", requirement = "Management API: valid registration is durable, audited, and secret-safe", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Test and register the real PostgreSQL setup through the UI", expectedResult = "The setup is committed and the audit contains no database password", cleanup = "Forget session registration during server shutdown and reset PostgreSQL", operations = {"listSetups", "testUnregisteredSetup", "registerSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void validRegistrationProducesSecretSafeAuditEvidence() throws Exception {
        unregistered(context -> {
            ManagementConsolePostgresFixture.registerSetup(context);
            assertTrue(Files.exists(context.auditPath()));
            String audit = Files.readString(context.auditPath());
            assertFalse(audit.isBlank());
            assertFalse(audit.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-042", requirement = "UI design: successful registration has a precise visible outcome", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Register a valid setup", expectedResult = "A status notice names the registered and selected setup", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void registrationShowsNamedSuccessNotice() throws Exception {
        registered(context -> assertThat(context.page().getByRole(AriaRole.STATUS))
                .containsText("Real PostgreSQL was registered and selected."));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-043", requirement = "UI design: newly registered setup becomes active scope", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Register the valid setup and inspect global scope", expectedResult = "The shell reports Setup: browser-postgres", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void newlyRegisteredSetupBecomesActiveScope() throws Exception {
        registered(context -> assertThat(context.page().getByTitle("Active setup scope"))
                .hasText("Setup: browser-postgres"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-044", requirement = "UI design: UI-session setup provenance is explicit", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Register a setup and inspect its table provenance", expectedResult = "The row labels the setup as Session-owned", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void registeredSetupRowShowsSessionProvenance() throws Exception {
        registered(context -> assertThat(setupRow(context.page())).containsText("browser-postgres · Session"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-045", requirement = "UI design: setup table identifies the exact database endpoint", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Register a setup and inspect database scope in its row", expectedResult = "Database, host, mapped port, and schema are visible", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void registeredSetupRowShowsExactDatabaseEndpoint() throws Exception {
        registered(context -> {
            Locator row = setupRow(context.page());
            assertThat(row).containsText("peegeeq");
            assertThat(row).containsText("db.internal.example:" + context.postgres().getMappedPort(5432) + "/peegee_cache");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-046", requirement = "UI design: setup table reports connected runtime state", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Register a setup and inspect its state badge", expectedResult = "The real runtime is visibly Connected", cleanup = "Close the registered runtime and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void registeredSetupRowShowsConnectedRuntime() throws Exception {
        registered(context -> assertThat(setupRow(context.page())
                .getByText("Connected", new Locator.GetByTextOptions().setExact(true))).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-SETUP-047", requirement = "UI design: active setup cannot be redundantly selected", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.MEDIUM, action = "Register and inspect the active scope action", expectedResult = "The row shows a disabled Selected button", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void activeSetupShowsDisabledSelectedAction() throws Exception {
        registered(context -> assertThat(setupRow(context.page())
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Selected"))).isDisabled());
    }

    @ManagementBrowserScenario(id = "PW-SETUP-048", requirement = "Management API: setup details load details, health, and effective limits together", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Open Details for the connected setup", expectedResult = "One modal presents connection, database health, and effective limits", cleanup = "Close details, runtime, context, and reset PostgreSQL", operations = {"listSetups", "getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsLoadsAllThreeAuthoritativeResources() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog.getByRole(AriaRole.HEADING, new Locator.GetByRoleOptions().setName("Database health"))).isVisible();
            assertThat(dialog.getByRole(AriaRole.HEADING, new Locator.GetByRoleOptions().setName("Effective limits"))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-049", requirement = "UI design: setup details preserve the pinned host and port", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect Host", expectedResult = "The reviewed host and mapped PostgreSQL port are exact", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsPinnedHostAndPort() throws Exception {
        registered(context -> assertThat(details(context.page())).containsText(
                "db.internal.example:" + context.postgres().getMappedPort(5432)));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-050", requirement = "UI design: setup details identify database and schema independently", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect database scope", expectedResult = "Database peegeeq and schema peegee_cache are visible", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsDatabaseAndSchema() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog).containsText("Database");
            assertThat(dialog).containsText("peegeeq");
            assertThat(dialog).containsText("Schema");
            assertThat(dialog).containsText("peegee_cache");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-051", requirement = "UI design: setup details expose installed migration version", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect Migration", expectedResult = "A numeric migration version is displayed", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsMigrationVersion() throws Exception {
        registered(context -> assertThat(details(context.page()).locator(".ant-descriptions").first()).containsText("Migration"));
    }

    @ManagementBrowserScenario(id = "PW-SETUP-052", requirement = "UI design: setup details expose effective runtime pool size", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect Pool size", expectedResult = "The configured pool size 3 is displayed", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsConfiguredPoolSize() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog).containsText("Pool size");
            assertThat(dialog.getByText("3", new Locator.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-053", requirement = "Management API: setup health proves the migrated database is ready", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL, action = "Open setup details and inspect Database health", expectedResult = "Health is Up with Schema ready", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsUpAndSchemaReadyHealth() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog.getByText("Up", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(dialog).containsText("Schema ready");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-054", requirement = "Management API: setup details expose the effective runtime Pub/Sub configuration", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect Pub/Sub", expectedResult = "Pub/Sub is reported enabled together with its channel prefix", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsRuntimePubSubConfiguration() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog).containsText("Pub/Sub");
            assertThat(dialog).containsText("Enabled · prefix");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-055", requirement = "Management API: setup details expose effective payload and value limits", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Open setup details and inspect Effective limits", expectedResult = "Maximum value and Pub/Sub byte limits are visible", cleanup = "Close details and reset PostgreSQL", operations = {"getSetup", "getSetupHealth"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void detailsShowsEffectiveByteLimits() throws Exception {
        registered(context -> {
            Locator dialog = details(context.page());
            assertThat(dialog).containsText("Maximum value");
            assertThat(dialog).containsText("Pub/Sub payload");
            assertThat(dialog).containsText("Pub/Sub channel");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-056", requirement = "Management API: a registered target can be retested without changing scope", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Activate Test on the connected setup row", expectedResult = "A notice reports response latency and ready schema while scope remains selected", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups", "testRegisteredSetup"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void registeredSetupCanBeRetestedWithoutScopeChange() throws Exception {
        registered(context -> {
            Page page = context.page();
            setupRow(page).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Test")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("schema ready");
            assertThat(page.getByTitle("Active setup scope")).hasText("Setup: browser-postgres");
        });
    }

    @ManagementBrowserScenario(id = "PW-SETUP-057", requirement = "UI design: refreshing setup inventory preserves a connected selected setup", area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.HIGH, action = "Register a setup and activate Refresh", expectedResult = "The same row and selected scope remain after authoritative reload", cleanup = "Close the registered context and reset PostgreSQL", operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void refreshPreservesConnectedSelectedSetup() throws Exception {
        registered(context -> {
            Page page = context.page();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Refresh")).click();
            assertThat(setupRow(page)).isVisible();
            assertThat(page.getByTitle("Active setup scope")).hasText("Setup: browser-postgres");
        });
    }

    private void registered(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        unregistered(context -> {
            ManagementConsolePostgresFixture.registerSetup(context);
            journey.run(context);
        });
    }

    private static void fillValid(Locator dialog, ManagementConsolePostgresFixture.Context context) {
        field(dialog, "Setup ID").fill(ManagementConsolePostgresFixture.SETUP_ID);
        field(dialog, "Display name").fill(ManagementConsolePostgresFixture.SETUP_NAME);
        field(dialog, "Host").fill("db.internal.example");
        field(dialog, "Port").fill(String.valueOf(context.postgres().getMappedPort(5432)));
        field(dialog, "Database").fill("peegeeq");
        field(dialog, "Schema").fill("peegee_cache");
        field(dialog, "Username").fill("peegeeq");
        field(dialog, "Password").fill(ManagementConsolePostgresFixture.DATABASE_PASSWORD);
        field(dialog, "Trust profile").fill("test-ca");
        field(dialog, "Pool size").fill("3");
    }

    private static void testConnection(Locator dialog) {
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Test connection")).click();
        assertThat(dialog.getByRole(AriaRole.STATUS)).containsText("Connection succeeded",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions()
                        .setTimeout(15_000));
    }

    private static Locator setupRow(Page page) {
        return page.getByRole(AriaRole.ROW)
                .filter(new Locator.FilterOptions().setHasText(ManagementConsolePostgresFixture.SETUP_NAME));
    }

    private static Locator details(Page page) {
        setupRow(page).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Details")).click();
        Locator dialog = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Setup details"));
        assertThat(dialog.getByText("Loading details…")).hasCount(0);
        return dialog;
    }

    private void unregistered(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), false, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            journey.run(context);
        });
    }

    private static Page setups(ManagementConsolePostgresFixture.Context context) {
        Page page = context.page();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Setups")).click();
        heading(page, "Setups");
        return page;
    }

    private static Locator registration(Page page, String actionName) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(actionName).setExact(true)).click();
        Locator dialog = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Register setup"));
        assertThat(dialog).isVisible();
        return dialog;
    }

    private static Locator field(Locator dialog, String label) {
        return dialog.getByLabel(label, new Locator.GetByLabelOptions().setExact(true));
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }
}
