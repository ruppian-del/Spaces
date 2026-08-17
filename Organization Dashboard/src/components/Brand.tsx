export function Brand({ compact = false, onDark = false }: { compact?: boolean; onDark?: boolean }) {
  return <div className={`brand ${compact ? 'brand--compact' : ''}`} aria-label="theSpaces.">
    {onDark
      ? <img className="brand-icon" src="/icon-dark.png" alt="" aria-hidden="true" />
      : <picture className="brand-icon" aria-hidden="true"><source srcSet="/icon-dark.png" media="(prefers-color-scheme: dark)" /><img src="/icon-light.png" alt="" /></picture>}
    <span>theSpaces.</span>
  </div>
}
