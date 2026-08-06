import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      // Bu kod tabanında localStorage sunucuda yok diye mount-sonrası hidrasyon deseni
      // (useState(false) + useEffect(() => setState(true), [])) tekrar eden, bilinçli bir
      // kalıp (bkz auth-provider.tsx, uygulama-provider.tsx yorumları). Kural bu deseni de
      // "cascading render" riski olarak işaretliyor; hata yerine uyarıya indirildi.
      "react-hooks/set-state-in-effect": "warn",
      // §0.3 NEVER listesi — panel üçüncü taraf içeriği (abone yanıtları) render ediyor.
      "react/no-danger": "error",
      "no-eval": "error",
      "no-new-func": "error",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
