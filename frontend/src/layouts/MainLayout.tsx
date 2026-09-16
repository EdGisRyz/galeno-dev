// Layout principal con navbar y footer

import { Outlet } from 'react-router-dom';

export function MainLayout() {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-blue-600 text-white px-6 py-4">
        <h1 className="text-lg font-bold">Galeno - Sistema de Gestión Médica</h1>
      </header>

      <main className="flex-1 p-6">
        <Outlet />
      </main>

      <footer className="bg-gray-100 text-center text-sm text-gray-500 py-4">
        Galeno © 2026
      </footer>
    </div>
  );
}