import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Joins class names and lets later Tailwind classes override earlier ones ("p-2 p-4" -> "p-4"). */
export function cn(...inputs) {
  return twMerge(clsx(inputs))
}
