# APK build verification

This branch exercises GitHub Actions' Android build job on a pull request, ensuring that the compiled debug APK can be made available as a downloadable workflow artifact once the build succeeds.

The application is **not** declared build-verified until the associated workflow completes successfully.
