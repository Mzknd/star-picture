package org.example.starpicbackend.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 外部资源操作只在明确的数据库提交/回滚之后执行。 */
public final class TransactionActions {
    private TransactionActions() { }
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    public static void afterRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) { action.run(); }
            }
        });
    }
}
