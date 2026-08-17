export const FOUNDATION_PLAN = { id: 'foundation', name: 'Foundation', monthlyPrice: 39, peopleCapacity: 250, activeSpaceCapacity: 10, storageCapacityGB: 10, moduleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'] } as const

export const CAPACITY_ADD_ONS = {
  people250: { id: 'people_250', name: '+250 unique members', monthlyPrice: 15, people: 250 },
  spaces10: { id: 'spaces_10', name: '+10 active Spaces', monthlyPrice: 15, spaces: 10 },
  storage100: { id: 'storage_100', name: '+100 GB storage', monthlyPrice: 15, storageGB: 100 },
  storage500: { id: 'storage_500', name: '+500 GB storage', monthlyPrice: 50, storageGB: 500 },
} as const

export const MODULE_ADD_ONS = [
  { id: 'communication', name: 'Communication', description: 'Announcements and Rooms', monthlyPrice: 15 },
  { id: 'agenda', name: 'Agenda', description: 'Lists and Notes', monthlyPrice: 12 },
  { id: 'content', name: 'Content', description: 'Media and Files', monthlyPrice: 12 },
] as const

export interface SubscriptionSelection { peoplePacks: number; spacePacks: number; storage100Packs: number; storage500Packs: number; moduleIds: string[] }

export function subscriptionMonthlyTotal(selection: SubscriptionSelection): number {
  const modules = MODULE_ADD_ONS.filter((module) => selection.moduleIds.includes(module.id)).reduce((total, module) => total + module.monthlyPrice, 0)
  return FOUNDATION_PLAN.monthlyPrice + selection.peoplePacks * 15 + selection.spacePacks * 15 + selection.storage100Packs * 15 + selection.storage500Packs * 50 + modules
}

export function subscriptionCapacity(selection: SubscriptionSelection) {
  return {
    people: FOUNDATION_PLAN.peopleCapacity + selection.peoplePacks * 250,
    spaces: FOUNDATION_PLAN.activeSpaceCapacity + selection.spacePacks * 10,
    storageGB: FOUNDATION_PLAN.storageCapacityGB + selection.storage100Packs * 100 + selection.storage500Packs * 500,
  }
}
