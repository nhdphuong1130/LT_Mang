# Admin Quick-Fill Implementation Plan

**Goal:** Add a login-screen button that fills admin username and password from a local, non-Git properties file.

**Architecture:** A focused loader reads and validates `username` and `password` from a configurable path, defaulting to `~/.ltm-server/quick-login.properties`. `LoginController` invokes the loader only when the operator presses the button and updates the existing JavaFX controls without submitting the login form.

**Tech Stack:** Java 21, JavaFX FXML, `java.util.Properties`, JUnit 5, Maven.

---

### Task 1: Credential loader

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/ui/QuickLoginCredentialsLoader.java`
- Create: `server/src/test/java/vn/edu/ltm/server/ui/QuickLoginCredentialsLoaderTest.java`

**Step 1:** Add failing tests proving valid properties load, missing files return an empty result, and blank username/password are rejected without exposing the password in an error.

**Step 2:** Run `./mvnw -pl server -am -Dtest=QuickLoginCredentialsLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test` and verify RED.

**Step 3:** Implement a loader using `Properties`, `Path`, and an immutable credentials record. Resolve the default path from `ltm.server.quickLoginFile` or `user.home` without embedding credentials.

**Step 4:** Re-run the targeted test and verify GREEN.

### Task 2: JavaFX button and controller behavior

**Files:**
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/LoginController.java`
- Modify: `server/src/main/resources/vn/edu/ltm/server/ui/login.fxml`
- Modify: `server/src/test/java/vn/edu/ltm/server/ui/LoginControllerTest.java`

**Step 1:** Add a failing controller test around a package-private quick-fill method, verifying both values are returned for valid configuration and invalid configuration produces a safe status without altering login authentication.

**Step 2:** Run the targeted controller test and verify RED.

**Step 3:** Add `#fillQuickAdmin` to the controller and an **Điền nhanh Admin** button to FXML. On success fill both controls; on failure show the config path and never log or display the password.

**Step 4:** Run both login tests and verify GREEN.

### Task 3: Verification and integration

**Files:**
- Modify: `README.md`

**Step 1:** Document the two-property local file and owner-only permission recommendation.

**Step 2:** Run `./mvnw clean verify` and require all tests and packaging to pass.

**Step 3:** Commit the implementation, fast-forward it into `main`, rebuild, restart the Server, and verify ports `5050/5051` plus the new login screen.
