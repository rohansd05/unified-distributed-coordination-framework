import { num } from './flowModel'
import { formatMillis, strategyLabel, strategyPhrase } from './labels'

/** Milliseconds as text: "623 ms", or "—" when not measured. */
export function ms(value) {
  const formatted = formatMillis(num(value))
  return formatted === null ? '—' : `${formatted} ms`
}

/**
 * What a FINISHED comparison measured, in plain sentences, gated exactly on the backend's
 * flags (finding.roundRobinFinishedLast, roundRobinMostEven, gainOverRoundRobinPercent) and
 * always with the measured numbers beside the claim. Nothing is claimed for a comparison that
 * is running, failed or has no finding.
 */
export function findingSentences(comparison) {
  const finding = comparison?.finding
  if (comparison?.state !== 'FINISHED' || !finding?.fastest) {
    return []
  }
  const phases = Array.isArray(comparison.phases) ? comparison.phases : []
  const phase = (strategy) => phases.find((p) => p.strategy === strategy)
  const roundRobin = phase('ROUND_ROBIN')
  const fastest = phase(finding.fastest)
  const slowest = phase(finding.slowest)
  const sentences = []

  if (finding.roundRobinFinishedLast === true) {
    sentences.push(`Round robin finished last: ${ms(roundRobin?.makespanMillis)}, against `
      + `${ms(fastest?.makespanMillis)} for ${strategyPhrase(finding.fastest)}.`)
  } else {
    sentences.push(`In this comparison round robin did not finish last: ${strategyPhrase(finding.fastest)} was `
      + `fastest (${ms(fastest?.makespanMillis)}), ${strategyPhrase(finding.slowest)} was slowest `
      + `(${ms(slowest?.makespanMillis)}), and round robin took ${ms(roundRobin?.makespanMillis)}.`)
  }
  if (finding.roundRobinMostEven === true) {
    const spread = num(roundRobin?.loadSpread)
    sentences.push(`Round robin's request counts were the most even of the four (spread ${spread ?? '—'})`
      + (finding.roundRobinFinishedLast === true ? ': equal request counts are not equal load.' : '.'))
  }
  const gain = num(finding.gainOverRoundRobinPercent)
  if (gain !== null) {
    sentences.push(`${strategyLabel(finding.fastest)} finished ${gain}% sooner than round robin.`)
  }
  return sentences
}
