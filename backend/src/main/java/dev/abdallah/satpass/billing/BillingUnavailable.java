package dev.abdallah.satpass.billing;

public class BillingUnavailable extends RuntimeException {
    public BillingUnavailable() { super("Billing is unavailable. Please retry later."); }
}
