package com.example.villageoverhaul.economy;

import com.example.villageoverhaul.economy.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anti-dupe and security tests for economy system
 * 
 * Constitution compliance:
 * - Principle X: Security & Anti-Exploit Posture
 * - Principle II: Deterministic Sync
 */
class EconomyAntiDupeTest {
    
    private WalletService walletService;
    private UUID playerId;
    private UUID villageId;
    
    @BeforeEach
    void setUp() {
        walletService = new WalletService();
        playerId = UUID.randomUUID();
        villageId = UUID.randomUUID();
    }
    
    @Test
    void testConcurrentCreditsNoDupe() throws InterruptedException {
        // Simulate concurrent credit operations
        int threadCount = 10;
        int creditsPerThread = 100;
        long amountPerCredit = 10L;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < creditsPerThread; j++) {
                        if (walletService.credit(playerId, amountPerCredit)) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify total balance matches successful credits
        long expectedBalance = successCount.get() * amountPerCredit;
        long actualBalance = walletService.getBalanceMillz(playerId);
        
        assertEquals(expectedBalance, actualBalance, 
            "Balance should match successful credits exactly (no duplication)");
        assertEquals(threadCount * creditsPerThread, successCount.get(),
            "All credit operations should succeed");
    }
    
    @Test
    void testConcurrentTransfersNoDoublespend() throws InterruptedException {
        // Setup: Player has initial balance
        long initialBalance = 10000L;
        walletService.credit(playerId, initialBalance);
        
        // Attempt concurrent transfers (total > balance)
        int threadCount = 20;
        long transferAmount = 1000L; // Total attempted: 20,000 > 10,000 balance
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        UUID[] recipients = new UUID[threadCount];
        for (int i = 0; i < threadCount; i++) {
            recipients[i] = UUID.randomUUID();
        }
        
        for (int i = 0; i < threadCount; i++) {
            final UUID recipient = recipients[i];
            executor.submit(() -> {
                try {
                    if (walletService.transfer(playerId, recipient, transferAmount)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify conservation of currency
        long playerBalance = walletService.getBalanceMillz(playerId);
        long totalRecipientBalance = 0;
        for (UUID recipient : recipients) {
            totalRecipientBalance += walletService.getBalanceMillz(recipient);
        }
        
        assertEquals(initialBalance, playerBalance + totalRecipientBalance,
            "Currency must be conserved (no double-spend)");
        
        // Only 10 transfers should succeed (10 * 1000 = 10,000)
        assertTrue(successCount.get() <= 10,
            "Should not allow transfers exceeding balance");
        assertEquals(initialBalance - (successCount.get() * transferAmount), playerBalance,
            "Player balance should reflect successful transfers only");
    }
    
    @Test
    void testOverflowProtection() {
        // Test credit overflow protection
        walletService.credit(playerId, Long.MAX_VALUE - 100);
        
        // Attempt to overflow
        boolean overflow = walletService.credit(playerId, 200);
        
        assertFalse(overflow, "Should reject credit that would overflow");
        assertEquals(Long.MAX_VALUE - 100, walletService.getBalanceMillz(playerId),
            "Balance should remain unchanged on overflow attempt");
    }
    
    @Test
    void testNegativeAmountRejection() {
        // Negative credits should throw
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.credit(playerId, -100);
        }, "Negative credit should be rejected");
        
        // Negative debits should throw
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.debit(playerId, -100);
        }, "Negative debit should be rejected");
        
        // Negative transfers should throw
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.transfer(playerId, villageId, -100);
        }, "Negative transfer should be rejected");
    }
    
    @Test
    void testZeroAmountRejection() {
        // Zero amounts should throw
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.credit(playerId, 0);
        }, "Zero credit should be rejected");
        
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.debit(playerId, 0);
        }, "Zero debit should be rejected");
        
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.transfer(playerId, villageId, 0);
        }, "Zero transfer should be rejected");
    }
    
    @Test
    void testInsufficientFundsProtection() {
        // Attempt debit with no balance
        boolean result = walletService.debit(playerId, 100);
        
        assertFalse(result, "Should reject debit when insufficient funds");
        assertEquals(0, walletService.getBalanceMillz(playerId),
            "Balance should remain zero");
        
        // Add some balance and attempt over-debit
        walletService.credit(playerId, 50);
        result = walletService.debit(playerId, 100);
        
        assertFalse(result, "Should reject debit exceeding balance");
        assertEquals(50, walletService.getBalanceMillz(playerId),
            "Balance should remain unchanged on failed debit");
    }
    
    @Test
    void testTransferDestinationOverflowProtection() {
        // Setup recipient near max balance
        walletService.credit(villageId, Long.MAX_VALUE - 50);
        walletService.credit(playerId, 100);
        
        // Attempt transfer that would overflow destination
        boolean result = walletService.transfer(playerId, villageId, 100);
        
        assertFalse(result, "Should reject transfer that would overflow destination");
        assertEquals(100, walletService.getBalanceMillz(playerId),
            "Source balance should remain unchanged");
        assertEquals(Long.MAX_VALUE - 50, walletService.getBalanceMillz(villageId),
            "Destination balance should remain unchanged");
    }
    
    @Test
    void testTransactionAuditLog() {
        // Perform several operations
        walletService.credit(playerId, 1000);
        walletService.debit(playerId, 200);
        walletService.transfer(playerId, villageId, 300);
        
        // Verify transactions are logged
        var wallet = walletService.getWallet(playerId);
        var transactions = wallet.getTransactions();
        
        assertTrue(transactions.size() >= 3, 
            "Should have at least 3 transactions logged");
        
        // Verify transaction types are recorded
        boolean hasCredit = transactions.stream()
            .anyMatch(tx -> tx.getDescription().contains("credit"));
        boolean hasDebit = transactions.stream()
            .anyMatch(tx -> tx.getDescription().contains("debit"));
        boolean hasTransfer = transactions.stream()
            .anyMatch(tx -> tx.getDescription().contains("transfer"));
        
        assertTrue(hasCredit, "Should log credit transactions");
        assertTrue(hasDebit, "Should log debit transactions");
        assertTrue(hasTransfer, "Should log transfer transactions");
    }
}
