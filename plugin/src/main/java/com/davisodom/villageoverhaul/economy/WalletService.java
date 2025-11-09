package com.davisodom.villageoverhaul.economy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative wallet service for Dollaz economy
 * 
 * Base currency: Dollaz with denominations Millz, Billz, Trills
 * - 100 Millz = 1 Billz
 * - 100 Billz = 1 Trills (i.e., 10,000 Millz = 1 Trills)
 * 
 * All arithmetic uses int64 Millz (smallest unit) for deterministic, lossless math.
 * No floating point. Auto-condensation and change-making in display logic.
 * 
 * Constitution compliance:
 * - Principle II: Deterministic, server-authoritative
 * - Principle X: Input validation, no client authority
 */
public class WalletService {
    
    private final Map<UUID, Wallet> wallets;
    
    // Denomination constants
    public static final long MILLZ_PER_BILLZ = 100L;
    public static final long MILLZ_PER_TRILLS = 10000L;
    public static final long MAX_BALANCE_MILLZ = Long.MAX_VALUE;
    
    public WalletService() {
        this.wallets = new ConcurrentHashMap<>();
    }
    
    /**
     * Get or create wallet for an owner
     * 
     * @param ownerId Player or village UUID
     * @return Wallet instance
     */
    public Wallet getWallet(UUID ownerId) {
        return wallets.computeIfAbsent(ownerId, id -> new Wallet(id));
    }
    
    /**
     * Credit Millz to a wallet (deterministic, atomic)
     * 
     * @param ownerId Wallet owner
     * @param millz Amount in Millz (must be > 0)
     * @return true if successful, false if would overflow
     */
    public boolean credit(UUID ownerId, long millz) {
        if (millz <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive: " + millz);
        }
        
        Wallet wallet = getWallet(ownerId);
        return wallet.credit(millz);
    }
    
    /**
     * Debit Millz from a wallet (deterministic, atomic)
     * 
     * @param ownerId Wallet owner
     * @param millz Amount in Millz (must be > 0)
     * @return true if successful, false if insufficient funds
     */
    public boolean debit(UUID ownerId, long millz) {
        if (millz <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive: " + millz);
        }
        
        Wallet wallet = getWallet(ownerId);
        return wallet.debit(millz);
    }
    
    /**
     * Transfer Millz between wallets (atomic)
     * 
     * @param fromId Source wallet
     * @param toId Destination wallet
     * @param millz Amount in Millz
     * @return true if successful
     */
    public boolean transfer(UUID fromId, UUID toId, long millz) {
        if (millz <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive: " + millz);
        }
        
        Wallet from = getWallet(fromId);
        Wallet to = getWallet(toId);
        
        // Atomic two-phase commit
        synchronized (from) {
            synchronized (to) {
                if (from.getBalanceMillz() < millz) {
                    return false; // Insufficient funds
                }
                
                // Check overflow before crediting
                if (millz > MAX_BALANCE_MILLZ - to.getBalanceMillz()) {
                    return false; // Would overflow destination
                }
                
                from.debit(millz);
                to.credit(millz);
                
                // Log transaction
                from.logTransaction(new Transaction(-millz, "transfer_to_" + toId));
                to.logTransaction(new Transaction(millz, "transfer_from_" + fromId));
                
                return true;
            }
        }
    }
    
    /**
     * Get balance in Millz
     */
    public long getBalanceMillz(UUID ownerId) {
        return getWallet(ownerId).getBalanceMillz();
    }
    
    /**
     * Get all wallets (for persistence)
     */
    public Map<UUID, Wallet> getAllWallets() {
        return new HashMap<>(wallets);
    }
    
    /**
     * Load wallet state (from persistence)
     */
    public void loadWallet(UUID ownerId, long balanceMillz, List<Transaction> transactions) {
        Wallet wallet = new Wallet(ownerId);
        wallet.balanceMillz = balanceMillz;
        wallet.transactions.addAll(transactions);
        wallets.put(ownerId, wallet);
    }
    
    /**
     * Individual wallet
     */
    public static class Wallet {
        private final UUID ownerId;
        private long balanceMillz;
        private final List<Transaction> transactions;
        
        public Wallet(UUID ownerId) {
            this.ownerId = ownerId;
            this.balanceMillz = 0L;
            this.transactions = new ArrayList<>();
        }
        
        public synchronized boolean credit(long millz) {
            // Overflow guard: check if addition would exceed MAX_BALANCE_MILLZ
            if (millz > MAX_BALANCE_MILLZ - balanceMillz) {
                return false; // Would overflow
            }
            balanceMillz += millz;
            logTransaction(new Transaction(millz, "credit"));
            return true;
        }
        
        public synchronized boolean debit(long millz) {
            if (balanceMillz < millz) {
                return false; // Insufficient funds
            }
            balanceMillz -= millz;
            logTransaction(new Transaction(-millz, "debit"));
            return true;
        }
        
        public long getBalanceMillz() {
            return balanceMillz;
        }
        
        public UUID getOwnerId() {
            return ownerId;
        }
        
        public List<Transaction> getTransactions() {
            return new ArrayList<>(transactions);
        }
        
        public void logTransaction(Transaction tx) {
            transactions.add(tx);
            // Limit transaction history to last 1000 for memory
            if (transactions.size() > 1000) {
                transactions.remove(0);
            }
        }
        
        /**
         * Format balance as Dollaz with auto-condensed denominations
         * Example: 12,345 Millz → "1 Trills, 23 Billz, 45 Millz"
         */
        public String formatDollaz() {
            long trills = balanceMillz / MILLZ_PER_TRILLS;
            long remainder = balanceMillz % MILLZ_PER_TRILLS;
            long billz = remainder / MILLZ_PER_BILLZ;
            long millz = remainder % MILLZ_PER_BILLZ;
            
            List<String> parts = new ArrayList<>();
            if (trills > 0) parts.add(trills + " Trills");
            if (billz > 0) parts.add(billz + " Billz");
            if (millz > 0) parts.add(millz + " Millz");
            
            return parts.isEmpty() ? "0 Millz" : String.join(", ", parts);
        }
        
        /**
         * Get denomination breakdown
         */
        public DenominationBreakdown getBreakdown() {
            long trills = balanceMillz / MILLZ_PER_TRILLS;
            long remainder = balanceMillz % MILLZ_PER_TRILLS;
            long billz = remainder / MILLZ_PER_BILLZ;
            long millz = remainder % MILLZ_PER_BILLZ;
            return new DenominationBreakdown(trills, billz, millz);
        }
    }
    
    /**
     * Transaction record (audit log)
     */
    public static class Transaction {
        private final long amountMillz;
        private final String description;
        private final long timestamp;
        
        public Transaction(long amountMillz, String description) {
            this.amountMillz = amountMillz;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }
        
        public long getAmountMillz() { return amountMillz; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * Denomination breakdown
     */
    public static class DenominationBreakdown {
        public final long trills;
        public final long billz;
        public final long millz;
        
        public DenominationBreakdown(long trills, long billz, long millz) {
            this.trills = trills;
            this.billz = billz;
            this.millz = millz;
        }
    }
}

