## What changed

<!-- One or two sentences. Link the issue if there is one. -->

## Why

## How it was tested

<!-- Device / Android version, and what you did on it. Unit tests count too. -->

## Checklist

- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, ...)
- [ ] `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` passes locally
- [ ] If this touches the trash queue, rewind or `MediaStore` requests: I tested it on a throwaway folder, not on real photos
