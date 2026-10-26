package ppa.cms.support;

import java.util.List;

public record PageSlice<T>(List<T> items, int currentPage, int lastPage, int perPage, long total) {

    public static int lastPage(long total, int perPage) {
        if (total <= 0) {
            return 1;
        }
        return (int) Math.ceil(total / (double) perPage);
    }

    public Integer from() {
        if (total == 0 || items.isEmpty()) {
            return null;
        }
        return (currentPage - 1) * perPage + 1;
    }

    public Integer to() {
        if (total == 0 || items.isEmpty()) {
            return null;
        }
        return (currentPage - 1) * perPage + items.size();
    }
}
