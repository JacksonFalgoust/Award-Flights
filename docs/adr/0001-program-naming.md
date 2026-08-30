# Name `Program` constants after the mileage program's brand, not seats.aero's slug

seats.aero's concepts data lists each partner under two columns: a `Source` slug
(`aeroplan`, `virginatlantic`) and a `Mileage Program` display name ("Air Canada
Aeroplan", "Virgin Atlantic Flying Club"). The docs treat these as one concept, not
two, so `Program` models a single enum — but something still has to name the
constants, and `Program.name()` is what `availability_entry.program` and
`watch.programs` persist. Renaming a constant later is therefore a data migration
over append-only history, not a refactor, which makes the choice worth recording
rather than picking by habit.

We name the constants after the program's own brand (`AEROPLAN`, `SKYMILES`,
`FLYING_CLUB`), not seats.aero's lowercase slug. The slug stays out of the domain
enum entirely and lives only in `SeatsAeroSource`'s `EnumMap<Program,String>` and
its reverse `Map<String,Program>`, beside that source's other vendor-specific
spellings (`cabin=economy` and the like). A second `AvailabilitySource` would bring
its own identifiers and has no claim on a privileged `code()` on the domain enum.

## Consequences

- There is deliberately no `UNKNOWN` fallback constant. An unrecognized seats.aero
  slug is skipped and logged by the mapper rather than persisted — two different
  unrecognized programs collapsing into one row would produce false `CHEAPER`
  alerts in the §6 diff.
- Adding a program seats.aero introduces later means adding an enum constant, not
  touching persisted data. Renaming an existing one means a migration.
