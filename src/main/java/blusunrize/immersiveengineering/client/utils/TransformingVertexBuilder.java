package blusunrize.immersiveengineering.client.utils;

import com.google.common.base.Preconditions;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.caffeinemc.mods.sodium.api.vertex.attributes.CommonVertexAttribute;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import static com.mojang.blaze3d.vertex.DefaultVertexFormat.*;

public class TransformingVertexBuilder implements VertexConsumer, VertexBufferWriter {
	private final VertexConsumer base;
	private final PoseStack transform;
	private final List<ObjectWithGlobal<?>> allObjects = new ArrayList<>();
	private final ObjectWithGlobal<Vec2> uv = new ObjectWithGlobal<>(this);
	private final ObjectWithGlobal<Vec3> pos = new ObjectWithGlobal<>(this);
	private final ObjectWithGlobal<Vec2i> overlay = new ObjectWithGlobal<>(this);
	private final ObjectWithGlobal<Vec2i> lightmap = new ObjectWithGlobal<>(this);
	private final ObjectWithGlobal<Vector3f> normal = new ObjectWithGlobal<>(this);
	private final ObjectWithGlobal<Vector4f> color = new ObjectWithGlobal<>(this);
	private final VertexFormat format;

	public TransformingVertexBuilder(VertexConsumer base, PoseStack transform, VertexFormat format) {
		this.base = base;
		this.transform = transform;
		this.format = format;
	}

	public TransformingVertexBuilder(VertexConsumer base, VertexFormat format) {
		this(base, new PoseStack(), format);
	}

	public TransformingVertexBuilder(MultiBufferSource buffer, RenderType type, PoseStack transform) {
		this(buffer.getBuffer(type), transform, type.format());
	}

	public TransformingVertexBuilder(MultiBufferSource buffer, RenderType type) {
		this(buffer, type, new PoseStack());
	}

	@Nonnull
	@Override
	public VertexConsumer vertex(double x, double y, double z) {
		pos.putData(new Vec3(x, y, z));
		return this;
	}

	@Nonnull
	@Override
	public VertexConsumer color(int red, int green, int blue, int alpha) {
		color.putData(new Vector4f(red / 255f, green / 255f, blue / 255f, alpha / 255f));
		return this;
	}

	@Nonnull
	@Override
	public VertexConsumer uv(float u, float v) {
		uv.putData(new Vec2(u, v));
		return this;
	}

	@Nonnull
	@Override
	public VertexConsumer overlayCoords(int u, int v) {
		overlay.putData(new Vec2i(u, v));
		return this;
	}

	@Nonnull
	@Override
	public VertexConsumer uv2(int u, int v) {
		lightmap.putData(new Vec2i(u, v));
		return this;
	}

	@Nonnull
	@Override
	public VertexConsumer normal(float x, float y, float z) {
		normal.putData(new Vector3f(x, y, z));
		return this;
	}

	@Override
	public void endVertex() {
		for (VertexFormatElement element : format.getElements()) {
			if (element == ELEMENT_POSITION)
				pos.ifPresent(pos -> base.vertex(transform.last().pose(), (float) pos.x, (float) pos.y, (float) pos.z));
			else if (element == ELEMENT_COLOR)
				color.ifPresent(c -> base.color(c.x(), c.y(), c.z(), c.w()));
			else if (element == ELEMENT_UV0)
				uv.ifPresent(uv -> base.uv(uv.x, uv.y));
			else if (element == ELEMENT_UV1)
				overlay.ifPresent(overlay -> base.overlayCoords(overlay.x, overlay.y));
			else if (element == ELEMENT_UV2)
				lightmap.ifPresent(lightmap -> base.uv2(lightmap.x, lightmap.y));
			else if (element == ELEMENT_NORMAL)
				normal.ifPresent(
						normal -> base.normal(transform.last().normal(), normal.x(), normal.y(), normal.z())
				);
		}
		base.endVertex();
		allObjects.forEach(ObjectWithGlobal::clear);
	}

	@Override
	public void push(MemoryStack stack, long ptr, int count, VertexFormatDescription format) {
		// 获取顶点格式中各个元素的偏移量
		int positionOffset = format.getElementOffset(CommonVertexAttribute.POSITION);
		int colorOffset = format.getElementOffset(CommonVertexAttribute.COLOR);
		int uvOffset = format.getElementOffset(CommonVertexAttribute.TEXTURE);

		// 检查是否包含 overlay、light 和 normal 属性
		boolean hasOverlay = containsElement(format, CommonVertexAttribute.OVERLAY);
		boolean hasLight = containsElement(format, CommonVertexAttribute.LIGHT);
		boolean hasNormal = containsElement(format, CommonVertexAttribute.NORMAL);

		int overlayOffset = hasOverlay ? format.getElementOffset(CommonVertexAttribute.OVERLAY) : -1;
		int lightOffset = hasLight ? format.getElementOffset(CommonVertexAttribute.LIGHT) : -1;
		int normalOffset = hasNormal ? format.getElementOffset(CommonVertexAttribute.NORMAL) : -1;

		// 将顶点数据从指针 ptr 复制到 VertexConsumer
		for (int i = 0; i < count; i++) {
			long vertexPtr = ptr + (long) i * format.stride();

			// 读取顶点数据
			float x = MemoryUtil.memGetFloat(vertexPtr + positionOffset);
			float y = MemoryUtil.memGetFloat(vertexPtr + positionOffset + 4);
			float z = MemoryUtil.memGetFloat(vertexPtr + positionOffset + 8);

			float r = MemoryUtil.memGetFloat(vertexPtr + colorOffset);
			float g = MemoryUtil.memGetFloat(vertexPtr + colorOffset + 4);
			float b = MemoryUtil.memGetFloat(vertexPtr + colorOffset + 8);
			float a = MemoryUtil.memGetFloat(vertexPtr + colorOffset + 12);

			float u = MemoryUtil.memGetFloat(vertexPtr + uvOffset);
			float v = MemoryUtil.memGetFloat(vertexPtr + uvOffset + 4);

			// 将数据写入 VertexConsumer
			this.vertex(x, y, z)
					.color(r, g, b, a)
					.uv(u, v);

			// 处理 overlay UV（如果存在）
			if (hasOverlay) {
				int overlayUV = MemoryUtil.memGetInt(vertexPtr + overlayOffset);
				this.overlayCoords((overlayUV >> 16) & 0xFFFF, overlayUV & 0xFFFF);
			}

			// 处理 light UV（如果存在）
			if (hasLight) {
				int lightUV = MemoryUtil.memGetInt(vertexPtr + lightOffset);
				this.uv2(lightUV & 0xFFFF, (lightUV >> 16) & 0xFFFF);
			}

			// 处理法线（如果存在）
			if (hasNormal) {
				float normalX = MemoryUtil.memGetFloat(vertexPtr + normalOffset);
				float normalY = MemoryUtil.memGetFloat(vertexPtr + normalOffset + 4);
				float normalZ = MemoryUtil.memGetFloat(vertexPtr + normalOffset + 8);
				this.normal(normalX, normalY, normalZ);
			}

			this.endVertex();
		}
	}

	private boolean containsElement(VertexFormatDescription format, CommonVertexAttribute attribute) {
		try {
			format.getElementOffset(attribute);
			return true;
		} catch (NoSuchElementException e) {
			return false;
		}
	}

	public void defaultColor(float r, float g, float b, float a) {
		color.setGlobal(new Vector4f(r, g, b, a));
	}

	@Override
	public void defaultColor(int r, int g, int b, int a) {
		defaultColor(r / 255f, g / 255f, b / 255f, a / 255f);
	}

	@Override
	public void unsetDefaultColor() {
		color.setGlobal(null);
	}

	public void setUV(Vec2 uv) {
		this.uv.setGlobal(uv);
	}

	public void setLight(int light) {
		lightmap.setGlobal(new Vec2i(light & 255, light >> 16));
	}

	public void setNormal(float x, float y, float z) {
		Vector3f vec = new Vector3f(x, y, z);
		vec.normalize();
		normal.setGlobal(vec);
	}

	public void setOverlay(int packedOverlayIn) {
		overlay.setGlobal(new Vec2i(packedOverlayIn & 0xffff, packedOverlayIn >> 16));
	}

	private record Vec2i(int x, int y) {
	}

	private static class ObjectWithGlobal<T> {
		@Nullable
		private T obj;
		private boolean isGlobal;

		public ObjectWithGlobal(TransformingVertexBuilder builder) {
			builder.allObjects.add(this);
		}

		public void putData(T newVal) {
			Preconditions.checkState(obj == null || (isGlobal && obj.equals(newVal)));
			obj = newVal;
		}

		public void setGlobal(@Nullable T obj) {
			this.obj = obj;
			isGlobal = obj != null;
		}

		public T read() {
			T ret = Preconditions.checkNotNull(obj);
			if (!isGlobal)
				obj = null;
			return ret;
		}

		public boolean hasValue() {
			return obj != null;
		}

		public void ifPresent(Consumer<T> out) {
			if (hasValue())
				out.accept(read());
		}

		public void clear() {
			if (!isGlobal)
				obj = null;
		}
	}
}