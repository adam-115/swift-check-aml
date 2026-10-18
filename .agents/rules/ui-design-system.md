---
description: Standard UI Design System (Tailwind) for all web applications based on the SwiftCheck aesthetic
---

# UI Design System Guidelines

Whenever the USER asks to build a web application, dashboard, or UI component in ANY project, you MUST strictly follow this design system based on Tailwind CSS. This style provides a premium, modern, and clean B2B SaaS look.

## 1. Color Palette
- **Backgrounds (App)**: Use `bg-slate-50` for the main application background to keep it soft on the eyes.
- **Backgrounds (Cards/Containers)**: Use `bg-white` for content containers to make them pop against the app background.
- **Text (Primary)**: Use `text-slate-800` or `text-slate-900` for headings and strong text.
- **Text (Secondary)**: Use `text-slate-500` or `text-slate-600` for subtitles, labels, and descriptions.
- **Primary Accent Colors**: Use `emerald` (e.g., `bg-emerald-600`, `text-emerald-600`) or `blue` (e.g., `bg-blue-600`, `text-blue-600`) for primary buttons, active links, and important highlights.
- **Danger/Alerts**: Use `red` (e.g., `bg-red-50`, `text-red-600`, `border-red-200`) for errors, sanctions, or destructive actions.
- **Borders**: Use `border-slate-200` or `border-slate-100` for subtle separation between elements.

## 2. Component Structures
- **Cards & Panels**: All main content blocks MUST use the following classes: `bg-white rounded-xl shadow-sm border border-slate-200`.
- **Form Inputs & Selects**: `bg-slate-50 border border-slate-300 text-slate-900 text-sm rounded-lg focus:ring-blue-500 focus:border-blue-500 w-full p-2.5 transition-colors`.
- **Buttons (Primary)**: `bg-blue-600 hover:bg-blue-700 text-white font-medium py-2 px-4 rounded-lg shadow-sm transition-colors duration-200 flex items-center justify-center gap-2`.
- **Buttons (Secondary)**: `bg-slate-100 hover:bg-slate-200 text-slate-700 font-medium py-2 px-4 rounded-lg transition-colors`.
- **Badges / Status Pills**: `inline-flex items-center px-2.5 py-1 text-xs font-semibold rounded-full border`. Example success: `bg-green-100 text-green-800 border-green-200`.

## 3. General Aesthetics & Layout
- **Shadows**: Keep shadows soft (`shadow-sm` or `shadow-md`). Avoid harsh, dark shadows.
- **Rounding**: Use `rounded-lg` or `rounded-xl` consistently to give a modern SaaS look.
- **Spacing**: Use ample padding (e.g., `p-6` for cards, `mb-8` for sections, `gap-6` for grids) to let the interface breathe. Do not cram elements together.
- **Icons**: Always integrate SVG icons (like Heroicons or FontAwesome) next to titles, inside buttons, and in empty states to improve visual hierarchy.
- **Headers**: Page headers should have a title and a subtle description text underneath (`text-slate-500 mt-2`).
