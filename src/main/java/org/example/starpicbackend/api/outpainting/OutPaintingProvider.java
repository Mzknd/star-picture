package org.example.starpicbackend.api.outpainting;

import org.example.starpicbackend.model.dto.outpainting.OutPaintingParameters;

public interface OutPaintingProvider {
    ProviderTask create(String imageUrl, OutPaintingParameters parameters);
    ProviderTask query(String providerTaskId);
}
