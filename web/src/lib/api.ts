import { getToken } from "./auth";

const BASE_URL = "/api/v1";

export async function fetchWithAuth(endpoint: string, options: RequestInit = {}) {
  const token = getToken();

  const headers = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options.headers,
  };

  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    // Handle unauthorized - maybe redirect to login
    if (typeof window !== "undefined") {
        window.location.href = "/login";
    }
  }

  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: "An error occurred" }));
    throw new Error(error.message || response.statusText);
  }

  return response.json();
}
