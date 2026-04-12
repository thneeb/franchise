## Rules
The rules of the game can be found in docs/ruleset.md. The original rules are in the Franchise.pdf in the same folder.
I already put som e code files into the entity folder which give you travel costs and bonusses. You can reuse the files or build new ones, but keep the information inside.

## Architecture
The implementation should be based on Spring Boot latest stable version.
If we need other frameworks outside the Spring Boot environment ask me.
Use lombok.
Use pretty typed code
Use maven as a build tool.

Package structure should be as following
1. boundary.http: with REST-Controllers. Suffix Controller
2. control: with all the services classes. Suffix Sertvice
3. integration: for possible backend integrations
4. entity: for general value classes

Controller classes should use a API first approach with an generated interface which they should follow.

Write test classes for complex code.
When we discover a problem during our journey always write a regression test, that we don't hit the same problem again.

## Key source files

- `src/main/resources/api-specs/franchise.yaml`: OpenAPI spec. Drives the generated controller interface in `de.neebs.franchise.boundary.http`.
- `src/main/java/de/neebs/franchise/entity/City.java`: All 45 cities with their names and sizes (1 = small town, 2–8 = city with that many branch slots).
- `src/main/java/de/neebs/franchise/entity/Region.java`: All 10 regions with their member cities and influence point payouts (1st / 2nd / 3rd place).
- `src/main/java/de/neebs/franchise/control/Rules.java`: All city-to-city route connections with travel costs, and the income tables (per player count) including starting capital. Use `Rules.CONNECTIONS`, `Rules.calcIncome()`, and `Rules.initScores()`.
- `docs/ruleset.md`: Full game rules in English (derived from `docs/Franchise.pdf`).