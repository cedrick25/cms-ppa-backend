# CMS-PPA API

This repository is the Java API in `spring-api`. It is Java 25 and Spring Boot 4.1.2. Do not add PHP, Laravel, Composer, or Node.js to the running API.

- Read `DEPLOYMENT.md` before changing how the app is installed or started.
- Keep the JSON paths, status codes, and field names the browser app already uses.
- Keep Laravel-compatible bcrypt (`$2y$`) and `APP_KEY` encryption. A new key makes saved system passwords unreadable.
- Hibernate must not rebuild existing MySQL tables. `ddl-auto` stays `none`. New empty databases get tables from `schema.sql` only when `users` is missing.
- Run tests from `spring-api` with `.\gradlew.bat test`.
