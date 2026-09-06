package com.creastrix.platform.readymadeproduct.domain;

/** Internal durable processing state of one manual quantity-delta command. */
public enum ManualQuantityDeltaCommandState {
    REGISTERED,
    APPLIED,
    REJECTED
}
