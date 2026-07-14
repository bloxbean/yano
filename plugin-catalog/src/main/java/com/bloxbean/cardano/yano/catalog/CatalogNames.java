package com.bloxbean.cardano.yano.catalog;

final class CatalogNames {
    private CatalogNames() {
    }

    static String providerClass(String value) {
        return CatalogValidation.providerClass(value, "provider");
    }
}
