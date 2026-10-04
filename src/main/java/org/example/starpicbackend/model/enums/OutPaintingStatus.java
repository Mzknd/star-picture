package org.example.starpicbackend.model.enums;

public enum OutPaintingStatus {
    SUBMITTING, PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED, EXPIRED;

    public boolean polling() {
        return this == PENDING || this == RUNNING;
    }
}
