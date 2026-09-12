package org.yanoproject.catalog;

final class CatalogNames {
    private CatalogNames() {
    }

    static String providerClass(String value) {
        return CatalogValidation.providerClass(value, "provider");
    }
}
