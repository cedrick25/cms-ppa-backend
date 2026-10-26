# CMS-PPA API

JSON API for the Credentials Management System. The browser interface lives in `cms-ppa` and calls this service with a bearer token.

Follow [DEPLOYMENT.md](DEPLOYMENT.md) to install JDK 25, point the app at the existing MySQL database, build the JAR, and keep it running. Do not install PHP or Node.js for this API.

The API stores system credentials (encrypted at rest), checks whether production sites answer, copies recent audit rows from UAMS, CMIS, and PPIS, and records what people do inside CMS-PPA. One Java process on port 8080 does the API, the checks, the audit copy, and the daily cleanup.
