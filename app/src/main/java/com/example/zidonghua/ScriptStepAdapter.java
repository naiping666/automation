package com.example.zidonghua;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ScriptStepAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ========== ViewType 常量 ==========
    private static final int VIEW_TYPE_NORMAL = 0;
    private static final int VIEW_TYPE_IF = 1;

    private List<ScriptStep> steps;
    private OnItemClickListener listener;
    private OnIfStepListener ifStepListener;
    private Context context;

    // ========== 主列表点击接口 ==========
    public interface OnItemClickListener {
        void onItemClick(int position);
        void onItemDelete(int position);
    }

    // ========== IF 步骤专用接口 ==========
    public interface OnIfStepListener {
        void onIfStepExpandToggle(int position);
        void onAddSubStep(int position, boolean isThen);      // isThen = true 表示条件成立, false 表示条件不成立
        void onEditIfStep(int position);
        void onSubStepClick(int parentPosition, int subPosition, boolean isThen);
        void onSubStepDelete(int parentPosition, int subPosition, boolean isThen);
    }

    public ScriptStepAdapter(Context context, List<ScriptStep> steps, OnItemClickListener listener) {
        this.context = context;
        this.steps = steps;
        this.listener = listener;
    }

    public void setIfStepListener(OnIfStepListener ifStepListener) {
        this.ifStepListener = ifStepListener;
    }

    @Override
    public int getItemViewType(int position) {
        ScriptStep step = steps.get(position);
        return step.isConditionStep() ? VIEW_TYPE_IF : VIEW_TYPE_NORMAL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_IF) {
            View view = inflater.inflate(R.layout.item_script_if, parent, false);
            return new IfViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_step, parent, false);
            return new NormalViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ScriptStep step = steps.get(position);

        if (holder instanceof NormalViewHolder) {
            bindNormalViewHolder((NormalViewHolder) holder, step, position);
        } else if (holder instanceof IfViewHolder) {
            bindIfViewHolder((IfViewHolder) holder, step, position);
        }
    }

    // ========== 绑定普通步骤 ==========
    private void bindNormalViewHolder(NormalViewHolder holder, ScriptStep step, int position) {
        holder.tvType.setText(step.getTypeName());
        holder.tvDesc.setText(step.getDescription());

        // 加载图标
        Drawable icon = null;
        if (step.type == ScriptStep.TYPE_LAUNCH_APP && step.packageName != null) {
            try {
                PackageManager pm = context.getPackageManager();
                icon = pm.getApplicationIcon(step.packageName);
            } catch (Exception e) {
                // 找不到应用，使用默认图标
            }
        }

        if (icon == null) {
            int iconRes;
            switch (step.type) {
                case ScriptStep.TYPE_LAUNCH_APP:
                    iconRes = android.R.drawable.ic_menu_share;
                    break;
                case ScriptStep.TYPE_WAIT:
                    iconRes = android.R.drawable.ic_menu_agenda;
                    break;
                case ScriptStep.TYPE_CLICK:
                    iconRes = android.R.drawable.ic_menu_edit;
                    break;
                case ScriptStep.TYPE_SWIPE:
                    iconRes = android.R.drawable.ic_menu_manage;
                    break;
                case ScriptStep.TYPE_IMAGE_CLICK:
                    iconRes = android.R.drawable.ic_menu_camera;
                    break;
                case ScriptStep.TYPE_LONG_CLICK:
                    iconRes = android.R.drawable.ic_menu_rotate;
                    break;
                case ScriptStep.TYPE_TEXT:
                    iconRes = android.R.drawable.ic_menu_edit;
                    break;
                default:
                    iconRes = android.R.drawable.ic_menu_help;
            }
            icon = context.getDrawable(iconRes);
        }

        holder.ivIcon.setImageDrawable(icon);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(position);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onItemDelete(position);
        });
    }

    // ========== 绑定 IF 条件步骤 ==========
    private void bindIfViewHolder(IfViewHolder holder, ScriptStep step, int position) {
        // 条件文字
        String conditionText = step.conditionText != null ? step.conditionText : "未设置";
        holder.tvCondition.setText("IF 识别到 \"" + conditionText + "\"");

        // 展开/折叠图标旋转
        holder.ivExpand.setRotation(step.isExpanded ? 90 : 0);

        // ✅ 点击 IF 步骤主体 → 展开/折叠
        holder.itemView.setOnClickListener(v -> {
            if (ifStepListener != null) {
                ifStepListener.onIfStepExpandToggle(position);
            }
        });

        // ✅ 删除整个 IF 步骤
        holder.ivDelete.setOnClickListener(v -> {
            if (listener != null) listener.onItemDelete(position);
        });

        // ✅ 编辑按钮 → 跳转到子编辑器
        holder.btnEdit.setOnClickListener(v -> {
            if (ifStepListener != null) {
                ifStepListener.onEditIfStep(position);
            }
        });

        // ========== 条件成立时 (then) ==========
        List<ScriptStep> thenSteps = step.thenSteps != null ? step.thenSteps : new ArrayList<>();
        holder.tvThenCount.setText("✅ 条件成立时 (" + thenSteps.size() + "步)");

        if (step.isExpanded && !thenSteps.isEmpty()) {
            holder.rvThen.setVisibility(View.VISIBLE);
            holder.rvThen.setLayoutManager(new LinearLayoutManager(context));
            SubStepAdapter thenAdapter = new SubStepAdapter(context, thenSteps, true, position, ifStepListener);
            holder.rvThen.setAdapter(thenAdapter);
        } else {
            holder.rvThen.setVisibility(View.GONE);
        }

        // ✅ 添加 then 子步骤
        holder.btnAddThen.setOnClickListener(v -> {
            if (ifStepListener != null) {
                ifStepListener.onAddSubStep(position, true);
            }
        });

        // ========== 条件不成立时 (else) ==========
        List<ScriptStep> elseSteps = step.elseSteps != null ? step.elseSteps : new ArrayList<>();
        holder.tvElseCount.setText("❌ 条件不成立时 (" + elseSteps.size() + "步)");

        if (step.isExpanded && !elseSteps.isEmpty()) {
            holder.rvElse.setVisibility(View.VISIBLE);
            holder.rvElse.setLayoutManager(new LinearLayoutManager(context));
            SubStepAdapter elseAdapter = new SubStepAdapter(context, elseSteps, false, position, ifStepListener);
            holder.rvElse.setAdapter(elseAdapter);
        } else {
            holder.rvElse.setVisibility(View.GONE);
        }

        // ✅ 添加 else 子步骤
        holder.btnAddElse.setOnClickListener(v -> {
            if (ifStepListener != null) {
                ifStepListener.onAddSubStep(position, false);
            }
        });

        // ✅ 控制展开/折叠时子列表的显示
        int visibility = step.isExpanded ? View.VISIBLE : View.GONE;
        holder.rvThen.setVisibility(visibility);
        holder.rvElse.setVisibility(visibility);
        holder.btnAddThen.setVisibility(visibility);
        holder.btnAddElse.setVisibility(visibility);
        holder.btnEdit.setVisibility(visibility);
    }

    @Override
    public int getItemCount() {
        return steps.size();
    }

    // ========== ViewHolder: 普通步骤 ==========
    static class NormalViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvType, tvDesc;
        ImageButton btnDelete;

        public NormalViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvType = itemView.findViewById(R.id.tv_type);
            tvDesc = itemView.findViewById(R.id.tv_desc);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }

    // ========== ViewHolder: IF 条件步骤 ==========
    static class IfViewHolder extends RecyclerView.ViewHolder {
        TextView tvCondition;
        TextView tvThenCount, tvElseCount;
        RecyclerView rvThen, rvElse;
        ImageView ivExpand, ivDelete;
        View btnAddThen, btnAddElse, btnEdit;

        public IfViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCondition = itemView.findViewById(R.id.tv_if_condition);
            tvThenCount = itemView.findViewById(R.id.tv_then_count);
            tvElseCount = itemView.findViewById(R.id.tv_else_count);
            rvThen = itemView.findViewById(R.id.rv_then_steps);
            rvElse = itemView.findViewById(R.id.rv_else_steps);
            ivExpand = itemView.findViewById(R.id.iv_expand);
            ivDelete = itemView.findViewById(R.id.iv_delete);
            btnAddThen = itemView.findViewById(R.id.btn_add_then);
            btnAddElse = itemView.findViewById(R.id.btn_add_else);
            btnEdit = itemView.findViewById(R.id.iv_edit);
        }
    }

    // ========== 子步骤适配器（嵌套在 IF 内部） ==========
    static class SubStepAdapter extends RecyclerView.Adapter<SubStepAdapter.SubViewHolder> {

        private Context context;
        private List<ScriptStep> subSteps;
        private boolean isThen;
        private int parentPosition;
        private OnIfStepListener listener;

        public SubStepAdapter(Context context, List<ScriptStep> subSteps, boolean isThen, int parentPosition, OnIfStepListener listener) {
            this.context = context;
            this.subSteps = subSteps;
            this.isThen = isThen;
            this.parentPosition = parentPosition;
            this.listener = listener;
        }

        @NonNull
        @Override
        public SubViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_script_sub_step, parent, false);
            return new SubViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SubViewHolder holder, int position) {
            ScriptStep step = subSteps.get(position);
            holder.tvDesc.setText(step.getTypeName() + ": " + step.getDescription());

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSubStepClick(parentPosition, position, isThen);
                }
            });

            holder.ivDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSubStepDelete(parentPosition, position, isThen);
                }
            });
        }

        @Override
        public int getItemCount() {
            return subSteps != null ? subSteps.size() : 0;
        }

        static class SubViewHolder extends RecyclerView.ViewHolder {
            TextView tvDesc;
            ImageView ivDelete;

            public SubViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDesc = itemView.findViewById(R.id.tv_sub_step_desc);
                ivDelete = itemView.findViewById(R.id.iv_sub_delete);
            }
        }
    }
}