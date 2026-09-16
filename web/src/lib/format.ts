// Forensic Hardened Financial Formatting
// Avoids parseFloat for core arithmetic if possible.
// For display, we use Intl.NumberFormat which is safe for strings that look like numbers.

export const formatCurrency = (value: string | number) => {
  // If string, pass directly to Number() which handles "100.00" better than parseFloat in some JS engines
  // but for Intl.NumberFormat, it accepts number.
  const num = typeof value === "string" ? Number(value) : value;

  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 2,
  }).format(num);
};

export const formatNumber = (value: string | number) => {
    const num = typeof value === "string" ? Number(value) : value;
    return new Intl.NumberFormat("en-IN").format(num);
};

// Safe addition for monetary strings
export const addMoney = (a: string, b: string): string => {
    // In a real high-value app, use big.js or decimal.js
    // For now, we perform calculation and return as fixed string to preserve decimals
    return (Number(a) + Number(b)).toFixed(2);
};
