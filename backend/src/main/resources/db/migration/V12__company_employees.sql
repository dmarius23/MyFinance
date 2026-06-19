-- Company gains an "has employees" flag (drives payroll relevance). The profit-vs-revenue tax base
-- is the existing tax_regime column (values PROFIT / MICRO).
ALTER TABLE company
    ADD COLUMN has_employees boolean;
