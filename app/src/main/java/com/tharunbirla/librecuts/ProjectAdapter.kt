package com.tharunbirla.librecuts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tharunbirla.librecuts.utils.ProjectManager.SavedProjectItem
import com.tharunbirla.librecuts.utils.setBounceClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for displaying saved OnticCut (.lcprj) projects on the home screen.
 * Displays project title, duration, clip count, last modified date, visual thumbnail preview,
 * and options popup menu.
 */
class ProjectAdapter(
    private val onProjectClick: (SavedProjectItem) -> Unit,
    private val onProjectMenuClick: (View, SavedProjectItem) -> Unit
) : ListAdapter<SavedProjectItem, ProjectAdapter.ProjectViewHolder>(ProjectDiffCallback()) {

    companion object {
        private val thumbnailCache = object : LruCache<String, Bitmap>(40) {}
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return ProjectViewHolder(view, onProjectClick, onProjectMenuClick)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ProjectViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailJob()
    }

    class ProjectViewHolder(
        itemView: View,
        private val onProjectClick: (SavedProjectItem) -> Unit,
        private val onProjectMenuClick: (View, SavedProjectItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvProjectName: TextView = itemView.findViewById(R.id.tvProjectName)
        private val tvProjectDetails: TextView = itemView.findViewById(R.id.tvProjectDetails)
        private val ivProjectThumbnail: ImageView = itemView.findViewById(R.id.ivProjectThumbnail)
        private val layoutFallbackIcon: View = itemView.findViewById(R.id.layoutFallbackIcon)
        private val btnProjectMenu: ImageButton = itemView.findViewById(R.id.btnProjectMenu)

        private var thumbnailJob: Job? = null
        private val holderScope = CoroutineScope(Dispatchers.Main)

        fun cancelThumbnailJob() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }

        fun bind(item: SavedProjectItem) {
            cancelThumbnailJob()

            tvProjectName.text = item.name

            val clipText = if (item.clipCount == 1) "1 clip" else "${item.clipCount} clips"
            tvProjectDetails.text = if (item.durationMs > 0L) {
                "${item.formattedDate} • ${item.formattedDuration} • $clipText"
            } else {
                "${item.formattedDate} • $clipText"
            }

            itemView.setBounceClickListener {
                onProjectClick(item)
            }

            btnProjectMenu.setOnClickListener { v ->
                onProjectMenuClick(v, item)
            }

            // Thumbnail loading with LRU cache and background decoding
            val thumbFile = item.thumbnailFile
            if (thumbFile != null && thumbFile.exists()) {
                val cacheKey = "${thumbFile.absolutePath}_${thumbFile.lastModified()}"
                val cached = thumbnailCache.get(cacheKey)
                if (cached != null && !cached.isRecycled) {
                    ivProjectThumbnail.setImageBitmap(cached)
                    ivProjectThumbnail.visibility = View.VISIBLE
                    layoutFallbackIcon.visibility = View.GONE
                } else {
                    ivProjectThumbnail.setImageDrawable(null)
                    ivProjectThumbnail.visibility = View.GONE
                    layoutFallbackIcon.visibility = View.VISIBLE

                    thumbnailJob = holderScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            try {
                                BitmapFactory.decodeFile(thumbFile.absolutePath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            thumbnailCache.put(cacheKey, bitmap)
                            ivProjectThumbnail.setImageBitmap(bitmap)
                            ivProjectThumbnail.visibility = View.VISIBLE
                            layoutFallbackIcon.visibility = View.GONE
                        }
                    }
                }
            } else {
                ivProjectThumbnail.setImageDrawable(null)
                ivProjectThumbnail.visibility = View.GONE
                layoutFallbackIcon.visibility = View.VISIBLE
            }
        }
    }

    private class ProjectDiffCallback : DiffUtil.ItemCallback<SavedProjectItem>() {
        override fun areItemsTheSame(oldItem: SavedProjectItem, newItem: SavedProjectItem): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }

        override fun areContentsTheSame(oldItem: SavedProjectItem, newItem: SavedProjectItem): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.lastModified == newItem.lastModified &&
                    oldItem.durationMs == newItem.durationMs &&
                    oldItem.clipCount == newItem.clipCount &&
                    oldItem.thumbnailFile?.lastModified() == newItem.thumbnailFile?.lastModified()
        }
    }
}
