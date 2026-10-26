package ppa.cms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cms")
public class CmsProperties {

    private String appKey = "";
    private String displayTimezone = "Asia/Manila";
    private boolean schemaInit = true;
    private int bcryptRounds = 12;
    private String corsAllowedOrigins = "";
    private final Admin admin = new Admin();
    private final SignIns signIns = new SignIns();
    private final Notifications notifications = new Notifications();
    private final SystemStatus systemStatus = new SystemStatus();
    private final Audit audit = new Audit();

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getDisplayTimezone() {
        return displayTimezone;
    }

    public void setDisplayTimezone(String displayTimezone) {
        this.displayTimezone = displayTimezone;
    }

    public boolean isSchemaInit() {
        return schemaInit;
    }

    public void setSchemaInit(boolean schemaInit) {
        this.schemaInit = schemaInit;
    }

    public int getBcryptRounds() {
        return bcryptRounds;
    }

    public void setBcryptRounds(int bcryptRounds) {
        this.bcryptRounds = bcryptRounds;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public Admin getAdmin() {
        return admin;
    }

    public SignIns getSignIns() {
        return signIns;
    }

    public Notifications getNotifications() {
        return notifications;
    }

    public SystemStatus getSystemStatus() {
        return systemStatus;
    }

    public Audit getAudit() {
        return audit;
    }

    public static class Admin {
        private String name = "";
        private String username = "";
        private String email = "";
        private String password = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class SignIns {
        private int dashboardLimit = 8;
        private int retentionDays = 90;

        public int getDashboardLimit() {
            return dashboardLimit;
        }

        public void setDashboardLimit(int dashboardLimit) {
            this.dashboardLimit = dashboardLimit;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    public static class Notifications {
        private int dashboardLimit = 20;
        private int downAfterFailures = 2;
        private int readRetentionDays = 30;
        private int retentionDays = 90;

        public int getDashboardLimit() {
            return dashboardLimit;
        }

        public void setDashboardLimit(int dashboardLimit) {
            this.dashboardLimit = dashboardLimit;
        }

        public int getDownAfterFailures() {
            return downAfterFailures;
        }

        public void setDownAfterFailures(int downAfterFailures) {
            this.downAfterFailures = downAfterFailures;
        }

        public int getReadRetentionDays() {
            return readRetentionDays;
        }

        public void setReadRetentionDays(int readRetentionDays) {
            this.readRetentionDays = readRetentionDays;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    public static class SystemStatus {
        private int intervalMinutes = 5;
        private int timeoutSeconds = 5;
        private int connectTimeoutSeconds = 3;
        private int concurrency = 5;
        private int batchSize = 50;
        private int maxBackoffMinutes = 60;
        private int staleAfterMinutes = 10;
        private boolean verifyTls = true;

        public int getIntervalMinutes() {
            return intervalMinutes;
        }

        public void setIntervalMinutes(int intervalMinutes) {
            this.intervalMinutes = intervalMinutes;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxBackoffMinutes() {
            return maxBackoffMinutes;
        }

        public void setMaxBackoffMinutes(int maxBackoffMinutes) {
            this.maxBackoffMinutes = maxBackoffMinutes;
        }

        public int getStaleAfterMinutes() {
            return staleAfterMinutes;
        }

        public void setStaleAfterMinutes(int staleAfterMinutes) {
            this.staleAfterMinutes = staleAfterMinutes;
        }

        public boolean isVerifyTls() {
            return verifyTls;
        }

        public void setVerifyTls(boolean verifyTls) {
            this.verifyTls = verifyTls;
        }
    }

    public static class Audit {
        private int perPage = 20;
        private int cmsPerPage = 20;
        private int syncLimit = 2000;
        private int statementTimeoutMs = 8000;
        private final Source uams = new Source();
        private final Source cmis = new Source();
        private final Source ppis = new Source();

        public int getPerPage() {
            return perPage;
        }

        public void setPerPage(int perPage) {
            this.perPage = perPage;
        }

        public int getCmsPerPage() {
            return cmsPerPage;
        }

        public void setCmsPerPage(int cmsPerPage) {
            this.cmsPerPage = cmsPerPage;
        }

        public int getSyncLimit() {
            return syncLimit;
        }

        public void setSyncLimit(int syncLimit) {
            this.syncLimit = syncLimit;
        }

        public int getStatementTimeoutMs() {
            return statementTimeoutMs;
        }

        public void setStatementTimeoutMs(int statementTimeoutMs) {
            this.statementTimeoutMs = statementTimeoutMs;
        }

        public Source getUams() {
            return uams;
        }

        public Source getCmis() {
            return cmis;
        }

        public Source getPpis() {
            return ppis;
        }
    }

    public static class Source {
        private String host = "127.0.0.1";
        private int port = 3306;
        private String database = "";
        private String username = "root";
        private String password = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
