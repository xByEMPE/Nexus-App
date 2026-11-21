-- Permite que cualquier usuario autenticado de la app lea los jobs (.wtgm) guardados en wtgmFile.
-- Hoy solo los admin pueden leerlos (política "wtgmFile admin all"), así que los pilotos verían la lista vacía.
-- La escritura sigue reservada a admin.
create policy "wtgmFile read authenticated"
  on public."wtgmFile" for select to authenticated using (true);
