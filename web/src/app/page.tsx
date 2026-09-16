import Link from "next/link";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-24">
      <div className="z-10 max-w-5xl w-full items-center justify-between font-mono text-sm lg:flex">
        <h1 className="text-4xl font-bold text-primary mb-8">
          Tadiwa Print Buddy
        </h1>
        <div className="flex gap-4">
          <Link href="/login" className="bg-primary text-black px-6 py-2 rounded-lg font-bold">
            Login to Dashboard
          </Link>
        </div>
      </div>
    </main>
  );
}
