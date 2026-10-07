package cn.hnust.selection.service;

/** Delivery provider; verification codes are never returned by the API or logged. */
public interface EmailDelivery {
    boolean isConfigured();
    void sendCode(String address, String code, boolean binding);
}
