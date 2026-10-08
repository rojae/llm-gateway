-- Historical month-specific settings; no recurring fee is inferred.
CREATE TABLE monitoring_subscription_costs (
 tenant_id TEXT NOT NULL, account_id UUID NOT NULL, month DATE NOT NULL,
 amount NUMERIC(16,6), currency TEXT NOT NULL CHECK(currency IN ('USD','KRW')),
 PRIMARY KEY(tenant_id,account_id,month),
 FOREIGN KEY(tenant_id,account_id) REFERENCES monitoring_accounts(tenant_id,id),
 CHECK (extract(day FROM month)=1), CHECK(amount IS NULL OR (amount>=0 AND amount<=1000000000))
);
