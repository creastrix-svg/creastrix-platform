export type FavoriteSnapshot = Readonly<{ownerId:string;version:string;skus:readonly string[]}>;
export const allowedFavoriteSku=(v:unknown):v is string=>typeof v==='string'&&v.length<=200&&/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(v);

// A retired SKU is still removable: validate its grammar, not its presence in today's catalog.
export function readFavoriteSnapshot(value:Record<string,unknown>,owner:string):FavoriteSnapshot{
  const {ownerId,version,skus}=value;
  if(ownerId!==owner || typeof version!=='string' || !/^(0|[1-9][0-9]{0,18})$/.test(version)
     || BigInt(version)>9223372036854775807n || !Array.isArray(skus) || skus.length>1000 ||
     !skus.every(allowedFavoriteSku) || new Set(skus).size!==skus.length)throw Error('FAVORITES_READ_UNAVAILABLE');
  return {ownerId:owner,version,skus};
}
