# Contributing to exinder

Thanks for your interest. exinder is a small personal project, but issues and pull requests are welcome.

> [!IMPORTANT]
> This codebase was written by an AI and has not had a human code review. Reviews of existing code are as valuable as new features: if you read something that looks wrong, please open an issue.

## Ground rules

- **Data safety first.** The app moves real files to the trash. Anything that touches the trash queue, rewind, `SwipeViewModel`, `MediaRepository` or `SystemRequests` needs a unit test or a clear explanation of how it was tested, and must be tried on a throwaway folder, never on real photos.
- **One change per commit**, following [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `hotfix:`, `refactor:`, `style:`, `test:`, `docs:`, `ci:`, `chore:`.
- **Comments explain why**, not what. Keep them in English.
- **No network access.** The app has no `INTERNET` permission and should stay that way.

## Getting started

Requirements: JDK 17 or newer and the Android SDK with platform 37 (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=...`).

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # install on a connected phone
```

## Before opening a pull request

Run the same checks CI runs:

```bash
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

- `./gradlew ktlintFormat` fixes most style issues automatically.
- Lint has a baseline (`app/lint-baseline.xml`) for warnings that existed when the gate was added. New warnings fail the build; fix them rather than adding them to the baseline.

## Translations

UI strings live in `app/src/main/res/values/strings.xml` (English) and `values-pt-rBR/strings.xml` (Brazilian Portuguese). To add a language, add a `values-<locale>/strings.xml` and list the locale in `app/src/main/res/xml/locales_config.xml`.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE), the same license as the rest of the project.
