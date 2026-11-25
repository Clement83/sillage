# BRouter routing engine (vendored)

Sources of the routing library of [BRouter](https://github.com/abrensch/brouter), **v1.7.10**
(commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`), MIT licence (see `LICENSE`), unchanged.

Copied from the modules `brouter-util`, `brouter-codec`, `brouter-expressions`, `brouter-mapaccess` and
`brouter-core` (their `src/main/java`), merged into this one Java library so that the app builds from
source (F-Droid) without a binary dependency. Same version as the BRouter of the singletrack server,
so that the `.rd5` segments it serves and the `lookups.dat` in the app's assets match.

To update: replace `src/main/java` with the same five modules of the new tag, update `lookups.dat` in
`app/src/main/assets/brouter/` from that tag's `misc/profiles2/`, and this file.
