# Security policy

exinder runs on the device and collects no data. Its only network access is uploading the Google Photos albums the user sends, straight to Google, with an `appendonly` token that can't read the rest of the library. The main risk is not an attacker but a bug that sends the wrong file to the trash.

## Reporting

- **Data loss bugs** (a file trashed that shouldn't have been): open a regular [bug report](https://github.com/murandido/exinder/issues/new?template=bug_report.yml). Restore the file from your gallery's trash first.
- **Security vulnerabilities** (for example a way for another app to make exinder trash files): please report them privately through [GitHub security advisories](https://github.com/murandido/exinder/security/advisories/new) instead of a public issue.

This is a personal project maintained in spare time. There is no guaranteed response time, and only the latest commit on `main` is supported.
