# Archived Room schemas

These files preserve the exported Room database schemas 16 through 22 from
`shared/core-storage/schemas/com.pocketshell.core.storage.AppDatabase/` before
the Kotlin storage module is removed from the JS-first branch.

The source JSON bytes were copied unchanged. They document the database shape
that the #2860 installed-data reader must inspect; they do not include a user's
database or private files. Keep the files immutable while implementing the
reader and signed same-package upgrade proof.

| Version | SHA-256 |
|---:|---|
| 16 | `bf05e8a5a399787057b1d5c0a98b282106476c82494821c4fd819c36453c707e` |
| 17 | `c89ab160ca66fb794f7114193c6a109e2fe1f99a845cb273c366e5eb39215c1f` |
| 18 | `3751b5a9561427a28a556fcaa82cfa5e28d18195043a27b50f69880815d61724` |
| 19 | `b4a334d8180e943db0ffd2ba45bd148d09bedc20120f2af8a061ca348590ef59` |
| 20 | `3781684bb18d2bffd330452fb18e8345a29d84925e46fde6550681e1ba5b493a` |
| 21 | `d11195e4f8603f362aacb088896b3f332fa809021824430213ec0ccb5a706f45` |
| 22 | `62873b748132bb5b3a49960171c8120844431b0ca487fef90a778fb58f19fe66` |
