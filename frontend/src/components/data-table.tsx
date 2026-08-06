"use client";

import type { ReactNode } from "react";

export type Column<T> = {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
};

/**
 * Deliberately plain. plan §12 puts a real dashboard in Faz 6 but the operator screens
 * are lists of rows with a few actions — a table component that can be read in one sitting
 * beats a grid library whose behaviour has to be looked up.
 */
export function DataTable<T>({
  rows,
  columns,
  empty,
  testId,
  rowKey,
}: {
  rows: T[] | undefined;
  columns: Column<T>[];
  empty: string;
  testId: string;
  rowKey: (row: T) => string;
}) {
  if (!rows) {
    return <p className="py-8 text-sm text-muted-foreground">Loading…</p>;
  }
  if (rows.length === 0) {
    return (
      <p data-testid={`${testId}-empty`} className="py-8 text-sm text-muted-foreground">
        {empty}
      </p>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border bg-background">
      <table data-testid={testId} className="w-full text-sm">
        <thead className="border-b bg-muted/40 text-left">
          <tr>
            {columns.map((column) => (
              <th key={column.key} className="px-3 py-2 font-medium">
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} data-testid={`${testId}-row`} className="border-b last:border-0">
              {columns.map((column) => (
                <td key={column.key} className="px-3 py-2 align-top">
                  {column.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function PageHeader({ title, description }: { title: string; description?: string }) {
  return (
    <div className="mb-6">
      <h1 data-testid="page-title" className="text-2xl font-semibold">
        {title}
      </h1>
      {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
    </div>
  );
}
