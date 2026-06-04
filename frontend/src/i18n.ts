import i18n from "i18next";
import { initReactI18next } from "react-i18next";

// Bilingual RO/EN; RO is the default for client-facing copy. Catalogs are intentionally small
// for the scaffold — extend per module.
const resources = {
  ro: {
    translation: {
      "nav.dashboard": "Panou",
      "nav.companies": "Firme",
      "nav.statements": "Extrase & facturi",
      "nav.taxes": "Taxe & plăți",
      "nav.payroll": "Salarizare",
      "nav.reports": "Rapoarte",
      "nav.notifications": "Notificări",
      "nav.tasks": "Sarcini",
      "nav.tenants": "Administrare tenant",
      "auth.login": "Autentificare",
      "auth.logout": "Deconectare",
      "common.loading": "Se încarcă…",
      "companies.add": "Adaugă firmă",
      "company.representatives": "Reprezentanți",
      "company.treasury": "Conturi trezorerie",
      "company.invite": "Invită reprezentant",
      "company.vat": "TVA",
      "company.vatStatus": "Status TVA",
      "company.vatPeriod": "Perioadă TVA",
      "vatStatus.VAT_PAYER": "Plătitor TVA",
      "vatStatus.NON_VAT_PAYER": "Neplătitor TVA",
    },
  },
  en: {
    translation: {
      "nav.dashboard": "Dashboard",
      "nav.companies": "Companies",
      "nav.statements": "Statements & invoices",
      "nav.taxes": "Taxes & payments",
      "nav.payroll": "Payroll",
      "nav.reports": "Reports",
      "nav.notifications": "Notifications",
      "nav.tasks": "Tasks",
      "nav.tenants": "Tenant admin",
      "auth.login": "Sign in",
      "auth.logout": "Sign out",
      "common.loading": "Loading…",
      "companies.add": "Add company",
      "company.representatives": "Representatives",
      "company.treasury": "Treasury accounts",
      "company.invite": "Invite representative",
      "company.vat": "VAT",
      "company.vatStatus": "VAT status",
      "company.vatPeriod": "VAT period",
      "vatStatus.VAT_PAYER": "VAT payer",
      "vatStatus.NON_VAT_PAYER": "Non-VAT payer",
    },
  },
};

void i18n.use(initReactI18next).init({
  resources,
  lng: "ro",
  fallbackLng: "en",
  interpolation: { escapeValue: false },
});

export default i18n;
