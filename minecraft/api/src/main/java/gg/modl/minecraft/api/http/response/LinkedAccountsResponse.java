package gg.modl.minecraft.api.http.response;

import gg.modl.minecraft.api.Account;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class LinkedAccountsResponse {
    private List<Account> linkedAccounts;
    private int status;
    private int totalCount = -1;
    private int page = -1;
    private boolean hasMore;
    private boolean truncated;

    public int getTotalCount() {
        if (totalCount >= 0) return totalCount;
        return linkedAccounts != null ? linkedAccounts.size() : 0;
    }
}
