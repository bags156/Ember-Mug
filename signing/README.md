# Public signing key

`public-release.p12` signs every build of this app. It is public on purpose: store password
`android`, key alias `embermug`, key password `android`. Anyone can rebuild and sign the app
exactly as the releases are signed, and no repository secrets are involved.

What that means: the signature shows a build is compatible with the others, not who made it.
Anyone could sign a modified APK that Android would accept as an update to this app, so only
install APKs from this repository's Releases or from a build you made yourself.

Do not replace this file. A different key means every installed copy has to be uninstalled
before the next version will install.
