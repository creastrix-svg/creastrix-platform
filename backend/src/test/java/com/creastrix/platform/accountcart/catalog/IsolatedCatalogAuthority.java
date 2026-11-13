package com.creastrix.platform.accountcart.catalog;

import javax.sql.DataSource;

/** Test adapter factory: app wiring only accepts its dedicated loopback catalog and role. */
public final class IsolatedCatalogAuthority {
    private IsolatedCatalogAuthority() {}
    public static SharedCatalogAuthority from(DataSource catalog) { return new SharedCatalogAuthority(catalog); }
}
