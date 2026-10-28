# Admin Quick-Fill Design

## Goal

Add a **Điền nhanh Admin** button to the Server login screen so the local operator can fill both username and password without storing credentials in source code or Git.

## Design

The Server reads optional credentials from `~/.ltm-server/quick-login.properties`. The file contains `username` and `password` properties and remains outside the repository. Pressing the button loads the file at that moment and fills the login fields. Normal sign-in and initial-administrator creation remain unchanged.

The controller delegates file parsing to a small loader with an injectable path, allowing deterministic tests. Missing files, missing properties, blank values, unreadable files, or malformed input do not alter either field; the UI displays a concise setup message instead. The password is never logged, added to an audit record, placed in a system property, or committed to Git.

Example local file:

```properties
username=admin
password=your-existing-admin-password
```

On POSIX systems the application attempts to restrict a newly created example/config file to owner-only access. Existing files are never overwritten automatically. The feature is explicitly a local convenience for the Server operator; it does not bypass `AdminAuthService`, automatically submit the form, or weaken bcrypt authentication.

## Verification

- Unit tests cover successful loading and all invalid/missing configuration states.
- JavaFX controller test verifies that clicking quick-fill changes both fields only when valid credentials are available.
- Full Maven verification must remain green and produce the executable Server JAR.
